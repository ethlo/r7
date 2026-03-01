package com.ethlo.venturi.vlf;

import static com.ethlo.venturi.vlf.VlfConstants.ACTIVE_FILE_EXTENSION;
import static com.ethlo.venturi.vlf.VlfConstants.VLF_FILE_EXTENSION;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.journal.api.ExchangeCompletionListener;
import com.github.luben.zstd.Zstd;

public final class VenturiTailer
{
    public static final String COMPRESSED_EXTENSION = ".zst";
    private static final Logger logger = LoggerFactory.getLogger(VenturiTailer.class);
    private static final String CHECKPOINT_FILE = ".venturi_checkpoints";
    private final Map<String, Long> checkpoints = new HashMap<>();
    private final Path logDir;
    private final Duration minAge;
    private final JournalEventListener reassembler;
    private final Path checkpointPath;

    private long totalBytesRead = 0;
    private long estimatedTextSize = 0;

    public VenturiTailer(final Path logDir, final Duration minAge, final ExchangeCompletionListener output)
    {
        this.logDir = logDir;
        this.minAge = minAge;
        this.reassembler = new ExchangeReassembler(output);
        this.checkpointPath = logDir.resolve(CHECKPOINT_FILE);
        loadCheckpoints();
    }

    public void runTick() throws IOException
    {
        final Set<String> fullyProcessedKeys = new HashSet<>();

        try (final Stream<Path> s = Files.list(logDir))
        {
            s.filter(p -> {
                        final String name = p.getFileName().toString();
                        return //name.endsWith(VLF_FILE_EXTENSION) || // IMPORTANT: Avoid race-condition with compressed file!
                                name.endsWith(ACTIVE_FILE_EXTENSION) ||
                                        name.endsWith(COMPRESSED_EXTENSION);
                    })
                    .sorted((p1, p2) -> {
                        final FileMeta m1 = parseMeta(p1);
                        final FileMeta m2 = parseMeta(p2);
                        if (m1.shardId() != m2.shardId())
                        {
                            return Integer.compare(m1.shardId(), m2.shardId());
                        }
                        if (m1.timestamp() != m2.timestamp())
                        {
                            return Long.compare(m1.timestamp(), m2.timestamp());
                        }
                        return Integer.compare(m1.rotationCount(), m2.rotationCount());
                    })
                    .forEach(path -> {
                        try
                        {
                            final boolean isFinished = processFile(path);
                            if (isFinished)
                            {
                                fullyProcessedKeys.add(getStableKey(path));
                            }
                            checkDelete(path, fullyProcessedKeys);
                        }
                        catch (IOException e)
                        {
                            throw new UncheckedIOException(e);
                        }
                    });
        }

        logStats();
        totalBytesRead = 0;
        estimatedTextSize = 0;

        saveCheckpoints();
    }

    private boolean processFile(final Path path) throws IOException
    {
        final String key = getStableKey(path);
        final long offset = checkpoints.getOrDefault(key, 0L);
        final boolean isCompressed = path.toString().endsWith(COMPRESSED_EXTENSION);
        final boolean isActive = path.toString().endsWith(ACTIVE_FILE_EXTENSION);

        final long fileSize;
        try
        {
            fileSize = Files.size(path);
        }
        catch (NoSuchFileException e)
        {
            return false;
        }

        try (final RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
             final FileChannel channel = raf.getChannel())
        {
            final MappedByteBuffer mappedBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            mappedBuffer.order(ByteOrder.LITTLE_ENDIAN);

            final ByteBuffer processingBuffer;

            if (isCompressed)
            {
                final long decompressedSize = Zstd.getDirectByteBufferFrameContentSize(mappedBuffer, 0, (int) fileSize);
                if (decompressedSize <= 0 || decompressedSize <= offset)
                {
                    return true;
                }

                processingBuffer = ByteBuffer.allocateDirect((int) decompressedSize);
                processingBuffer.order(ByteOrder.BIG_ENDIAN);
                Zstd.decompress(processingBuffer, mappedBuffer);
                processingBuffer.position((int) offset);
            }
            else
            {
                if (fileSize <= offset)
                {
                    return !isActive;
                }
                processingBuffer = mappedBuffer;
                processingBuffer.position((int) offset);
            }

            final long before = processingBuffer.remaining();
            final long textLen = JournalDecoder.decode(processingBuffer, reassembler);
            totalBytesRead += before - processingBuffer.remaining();
            estimatedTextSize += textLen;
            checkpoints.put(key, (long) processingBuffer.position());
            return processingBuffer.remaining() == 0 && !isActive;
        }
    }

    private void logStats()
    {
        if (totalBytesRead > 0)
        {
            final String binarySize = totalBytesRead < 1024 ? totalBytesRead + " B" : String.format("%.2f KB", totalBytesRead / 1024.0);
            logger.info("Tailer Stats: Processed {} binary", binarySize);
        }
    }

    private void checkDelete(final Path path, final Set<String> fullyProcessedKeys) throws IOException
    {
        final String key = getStableKey(path);

        if (fullyProcessedKeys.contains(key))
        {
            if (minAge != null)
            {
                final long lastModified = Files.getLastModifiedTime(path).toMillis();
                if (System.currentTimeMillis() - lastModified < minAge.toMillis())
                {
                    return;
                }
            }

            Files.delete(path);
            checkpoints.remove(key);
            logger.info("Deleted completed segment: {}", path.getFileName());
        }
    }

    private FileMeta parseMeta(final Path path)
    {
        final String name = path.getFileName().toString();
        try
        {
            final String[] parts = name.split("[-.]");
            return new FileMeta(Integer.parseInt(parts[1]), Long.parseLong(parts[2]), Integer.parseInt(parts[3]));
        }
        catch (Exception e)
        {
            return new FileMeta(0, 0, 0);
        }
    }

    private String getStableKey(final Path path)
    {
        return path.getFileName().toString()
                .replace(ACTIVE_FILE_EXTENSION, "")
                .replace(COMPRESSED_EXTENSION, "")
                .replace(VLF_FILE_EXTENSION, "");
    }

    private void loadCheckpoints()
    {
        if (!Files.exists(checkpointPath))
        {
            return;
        }

        try (final InputStream in = Files.newInputStream(checkpointPath))
        {
            final Properties props = new Properties();
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

        final Path tempFile = checkpointPath.resolveSibling(CHECKPOINT_FILE + ".tmp");
        try
        {
            final Properties props = new Properties();
            checkpoints.forEach((k, v) -> props.setProperty(k, Long.toString(v)));

            try (final OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
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