package com.ethlo.venturi.vlf;

import com.ethlo.venturi.auditing.api.ExchangeCompletionListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

public class VenturiTailer
{
    private static final Logger logger = LoggerFactory.getLogger(VenturiTailer.class);
    private static final String CHECKPOINT_FILE = ".venturi_checkpoints";

    private final Map<String, Long> checkpoints = new HashMap<>();
    private final Map<String, VlfDictionary> dictCache = new HashMap<>();
    private final Path logDir;
    private final Duration minAge;
    private final JournalEventListener reassembler;
    private final Path checkpointPath;

    // Statistics tracking
    private long totalBytesRead = 0;
    private long estimatedTextSize = 0;

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
                        if (m1.shardId != m2.shardId)
                        {
                            return Integer.compare(m1.shardId, m2.shardId);
                        }
                        if (m1.timestamp != m2.timestamp)
                        {
                            return Long.compare(m1.timestamp, m2.timestamp);
                        }
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

        logStats();

        // Reset stats for next block
        this.totalBytesRead = 0;
        this.estimatedTextSize = 0;

        saveCheckpoints();
    }

    private void processFile(Path path) throws IOException
    {
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

                VlfDictionary dict = dictCache.get(key);
                if (dict == null)
                {
                    dict = JournalDecoder.readDictionaryFromPreamble(buffer);
                    dictCache.put(key, dict);
                }

                int startPos = (offset == 0) ? VlfConstants.PREAMBLE_SIZE : (int) offset;
                buffer.position(startPos);

                long bytesBefore = buffer.remaining();

                try
                {
                    long textLength = JournalDecoder.decode(buffer, dict, reassembler);
                    this.totalBytesRead += (bytesBefore - buffer.remaining());
                    this.estimatedTextSize += textLength;
                }
                catch (Exception e)
                {
                    logger.error("Decode error in {}: {}", path, e.getMessage());
                }

                checkpoints.put(key, (long) buffer.position());
            }
            finally
            {
                VlfJournal.unmap(buffer);
            }
        }
    }

    private void logStats()
    {
        if (totalBytesRead > 0)
        {
            double ratio = (1.0 - ((double) totalBytesRead / estimatedTextSize)) * 100.0;
            logger.info("Tailer Stats: Processed {} KB binary (Estimated {} KB text). Saved: {}%",
                    totalBytesRead / 1024,
                    estimatedTextSize / 1024,
                    String.format("%.2f", ratio));
        }
    }

    private void checkDelete(Path path) throws IOException
    {
        String key = getStableKey(path);
        if (path.toString().endsWith(".raw") && checkpoints.containsKey(key))
        {
            if (minAge != null)
            {
                long lastModified = Files.getLastModifiedTime(path).toMillis();
                long age = System.currentTimeMillis() - lastModified;
                if (age < minAge.toMillis())
                {
                    return;
                }
            }

            Files.delete(path);
            Path indexPath = path.resolveSibling(path.getFileName().toString() + ".index");
            Files.deleteIfExists(indexPath);
            checkpoints.remove(key);
            dictCache.remove(key);
            logger.info("Deleted completed segment: {}", path.getFileName());
        }
    }

    private FileMeta parseMeta(Path path)
    {
        String name = path.getFileName().toString();
        try
        {
            String[] parts = name.split("[-.]");
            return new FileMeta(
                    Integer.parseInt(parts[1]),
                    Long.parseLong(parts[2]),
                    Integer.parseInt(parts[3])
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
        return name.replace(".active", "").replace(".raw", "");
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
            props.forEach((k, v) -> {
                checkpoints.put((String) k, Long.parseLong((String) v));
            });
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
            checkpoints.forEach((key, value) -> {
                props.setProperty(key, Long.toString(value));
            });

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