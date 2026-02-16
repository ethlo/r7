package com.ethlo.venturi.vlf;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.ExchangeCompletionListener;

public class VenturiTailer
{
    private static final Logger logger = LoggerFactory.getLogger(VenturiTailer.class);
    private static final String CHECKPOINT_FILE = ".venturi_checkpoints";

    private final Map<String, Long> checkpoints = new HashMap<>();
    private final Path logDir;
    private final Duration minAge;
    private final JournalEventListener reassembler;
    private final Path checkpointPath;

    public VenturiTailer(Path logDir, Duration minAge, ExchangeCompletionListener output)
    {
        this.logDir = logDir;
        this.minAge = minAge;
        this.reassembler = new ExchangeReassembler(output);
        this.checkpointPath = logDir.resolve(CHECKPOINT_FILE);
        loadCheckpoints();
    }

    public void runTick() throws IOException
    {
        try (Stream<Path> s = Files.list(logDir))
        {
            s.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".raw") || name.endsWith(".active");
                    })
                    .sorted((p1, p2) -> {
                        FileMeta m1 = parseMeta(p1);
                        FileMeta m2 = parseMeta(p2);

                        // 1. Group by Shard (Crucial for Reassembler state)
                        if (m1.shardId != m2.shardId)
                        {
                            return Integer.compare(m1.shardId, m2.shardId);
                        }

                        // 2. Order by Timestamp (Chronological)
                        if (m1.timestamp != m2.timestamp)
                        {
                            return Long.compare(m1.timestamp, m2.timestamp);
                        }

                        // 3. Tie-break with Rotation Count
                        return Integer.compare(m1.rotationCount, m2.rotationCount);
                    })
                    .forEach(path -> {
                        try
                        {
                            processFile(path);
                            checkDelete(path);
                        }
                        catch (IOException e)
                        {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        saveCheckpoints();
    }

    private void checkDelete(Path path) throws IOException
    {
        String key = getStableKey(path);
        if (path.toString().endsWith(".raw") && checkpoints.containsKey(key))
        {
            // Check age if minAge is set
            if (minAge != null)
            {
                long lastModified = Files.getLastModifiedTime(path).toMillis();
                long age = System.currentTimeMillis() - lastModified;
                if (age < minAge.toMillis())
                {
                    return; // Too young to die
                }
            }

            Files.delete(path);
            Path indexPath = path.resolveSibling(path.getFileName().toString() + ".index");
            Files.deleteIfExists(indexPath);
            checkpoints.remove(key);
            logger.info("Deleted completed segment: {}", path.getFileName());
        }
    }

    private FileMeta parseMeta(Path path)
    {
        String name = path.getFileName().toString();
        try
        {
            // Split by '-' and '.' to get parts
            String[] parts = name.split("[-.]");
            return new FileMeta(
                    Integer.parseInt(parts[1]), // shardId
                    Long.parseLong(parts[2]),   // timestamp
                    Integer.parseInt(parts[3])  // rotationCount
            );
        }
        catch (Exception e)
        {
            logger.error("Failed to parse metadata from filename: {}", name);
            return new FileMeta(0, 0, 0);
        }
    }

    private String getStableKey(Path path)
    {
        String name = path.getFileName().toString();
        // shard-0-12345-1.active -> shard-0-12345-1
        // shard-0-12345-1.raw    -> shard-0-12345-1
        return name.replace(".active", "").replace(".raw", "");
    }

    private void processFile(Path path) throws IOException
    {
        // Always resolve the checkpoint against a path that doesn't care about the extension
        final String key = getStableKey(path);

        long offset = checkpoints.getOrDefault(key, 0L);

        long fileSize;
        try
        {
            fileSize = Files.size(path);
        }
        catch (NoSuchFileException e)
        {
            return;
        }

        if (fileSize <= offset)
        {
            return;
        }

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r"))
        {
            long fileLength = raf.length();
            MappedByteBuffer buffer = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, fileLength);
            try
            {
                buffer.order(ByteOrder.BIG_ENDIAN);
                buffer.position((int) offset);

                if (offset == 0 && buffer.remaining() > 0)
                {
                    byte version = buffer.get();
                    if (version != Marker.VERSION)
                    {
                        logger.error("Version mismatch in {}: expected {}, got {}", path, Marker.VERSION, version);
                    }
                }

                try
                {
                    JournalDecoder.decode(buffer, reassembler);
                }
                catch (RuntimeException e)
                {
                    // At 175k req/s, don't crash the whole process; log and update checkpoint to skip poison
                    logger.error("Critical decode error in {}: {}", path, e.getMessage());
                }

                checkpoints.put(key, (long) buffer.position());
            } finally
            {
                Journal.unmap(buffer);
            }
        }
    }

    private void loadCheckpoints()
    {
        if (!Files.exists(checkpointPath))
        {
            return;
        }

        try (InputStream in = Files.newInputStream(checkpointPath))
        {
            Properties props = new Properties();
            props.load(in);
            props.forEach((k, v) -> checkpoints.put((String) k, Long.parseLong((String) v)));
            logger.info("Restored {} stable checkpoints", checkpoints.size());
        }
        catch (IOException e)
        {
            logger.error("Load failed: {}", e.getMessage());
        }
    }

    private void saveCheckpoints()
    {
        if (checkpoints.isEmpty())
        {
            return;
        }

        Path tempFile = checkpointPath.resolveSibling(CHECKPOINT_FILE + ".tmp");
        try
        {
            Properties props = new Properties();
            // The key is already a stable string from our put() calls
            checkpoints.forEach((key, value) -> props.setProperty(key, Long.toString(value)));

            try (OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
            {
                props.store(out, "Venturi Tailer Progress");
            }
            Files.move(tempFile, checkpointPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException e)
        {
            logger.error("Save failed: {}", e.getMessage());
        }
    }

    private record FileMeta(int shardId, long timestamp, int rotationCount)
    {
    }
}