package com.ethlo.r7.r7f;

import static com.ethlo.r7.r7f.R7fConstants.ACTIVE_FILE_EXTENSION;
import static com.ethlo.r7.r7f.R7fConstants.R7F_FILE_EXTENSION;

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

import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.github.luben.zstd.Zstd;

public final class R7Tailer
{
    public static final String COMPRESSED_EXTENSION = ".zst";
    private static final Logger logger = LoggerFactory.getLogger(R7Tailer.class);
    private static final String CHECKPOINT_FILE = ".r7_checkpoints";

    private final Map<String, Long> checkpoints = new HashMap<>();
    private final Path logDir;
    private final Duration minAge;
    private final JournalEventListener reassembler;
    private final Path checkpointPath;

    private long totalBytesRead = 0;

    // Reusable off-heap buffer
    private ByteBuffer decompressionBuffer = ByteBuffer.allocateDirect(10 * 1024 * 1024);

    public R7Tailer(final Path logDir, final Duration minAge, final ExchangeCompletionListener output)
    {
        this.logDir = logDir;
        this.minAge = minAge;
        this.reassembler = new ExchangeReassembler(output);
        this.checkpointPath = logDir.resolve(CHECKPOINT_FILE);
        loadCheckpoints();
    }

    public long runTick() throws IOException
    {
        totalBytesRead = 0;
        final Set<String> fullyProcessedKeys = new HashSet<>();

        try (final Stream<Path> s = Files.list(logDir))
        {
            // Collect and deduplicate files by their stable prefix key
            // This safely resolves an .active file rotating to a time-bounded .zst file
            final Map<String, Path> resolvedFiles = new HashMap<>();

            s.filter(p -> {
                        final String name = p.getFileName().toString();
                        return name.endsWith(R7F_FILE_EXTENSION) ||
                                name.endsWith(ACTIVE_FILE_EXTENSION) ||
                                name.endsWith(COMPRESSED_EXTENSION);
                    })
                    .forEach(path -> {
                        final String key = getStableKey(path);
                        final Path existing = resolvedFiles.get(key);

                        // Priority: .zst > .r7f > .active
                        if (existing == null || isHigherPriority(path, existing))
                        {
                            resolvedFiles.put(key, path);
                        }
                    });

            // Sort the resolved files and process them sequentially
            resolvedFiles.values().stream()
                    .sorted((p1, p2) -> {
                        final FileMeta m1 = parseMeta(p1);
                        final FileMeta m2 = parseMeta(p2);
                        if (m1.shardId() != m2.shardId())
                        {
                            return Integer.compare(m1.shardId(), m2.shardId());
                        }
                        return Long.compare(m1.firstEventNanos(), m2.firstEventNanos());
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
                        catch (final IOException e)
                        {
                            throw new UncheckedIOException(e);
                        }
                    });
        }

        logStats();
        saveCheckpoints();
        return totalBytesRead;
    }

    private boolean isHigherPriority(final Path newPath, final Path existingPath)
    {
        final String newStr = newPath.toString();
        final String existingStr = existingPath.toString();

        if (newStr.endsWith(COMPRESSED_EXTENSION))
        {
            return true;
        }
        return newStr.endsWith(R7F_FILE_EXTENSION) && existingStr.endsWith(ACTIVE_FILE_EXTENSION);
    }

    private boolean processFile(final Path path) throws IOException
    {
        final String key = getStableKey(path);
        final long offset = checkpoints.getOrDefault(key, 0L);

        // Sentinel check: File is completely read, do not waste CPU decompressing it
        if (offset == -1L)
        {
            return true;
        }

        final boolean isCompressed = path.toString().endsWith(COMPRESSED_EXTENSION);
        final boolean isActive = path.toString().endsWith(ACTIVE_FILE_EXTENSION);

        // Enforce the preamble boundary so FlatBuffers never sees the manual binary header
        final long startOffset = Math.max(offset, R7fConstants.PREAMBLE_SIZE);

        final long fileSize;
        try
        {
            fileSize = Files.size(path);
        }
        catch (final NoSuchFileException e)
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
                if (decompressedSize <= 0 || decompressedSize <= startOffset)
                {
                    return true;
                }

                // Expand reusable buffer only if necessary
                if (decompressionBuffer.capacity() < decompressedSize)
                {
                    decompressionBuffer = ByteBuffer.allocateDirect((int) decompressedSize);
                }

                processingBuffer = decompressionBuffer.slice(0, (int) decompressedSize);
                processingBuffer.order(ByteOrder.LITTLE_ENDIAN);

                Zstd.decompress(processingBuffer, mappedBuffer);
                processingBuffer.position((int) startOffset);
            }
            else
            {
                if (fileSize <= startOffset)
                {
                    return !isActive;
                }
                processingBuffer = mappedBuffer;
                processingBuffer.position((int) startOffset);
            }

            final long before = processingBuffer.remaining();
            JournalDecoder.decode(processingBuffer, reassembler);
            totalBytesRead += before - processingBuffer.remaining();

            final boolean isFinished = processingBuffer.remaining() == 0 && !isActive;

            if (isFinished)
            {
                checkpoints.put(key, -1L);
            }
            else
            {
                checkpoints.put(key, (long) processingBuffer.position());
            }

            return isFinished;
        }
    }

    private void logStats()
    {
        if (totalBytesRead > 0)
        {
            logger.info("Tailer Stats: Processed {}", DiskSpaceUtils.formatBytes(totalBytesRead));
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
        final String name = path.getFileName().toString()
                .replace(ACTIVE_FILE_EXTENSION, "")
                .replace(COMPRESSED_EXTENSION, "")
                .replace(R7F_FILE_EXTENSION, "");
        try
        {
            final String[] parts = name.split("-");
            final int shardId = Integer.parseInt(parts[1]);
            final long firstEventNanos = Long.parseLong(parts[2]);
            final long lastEventNanos = parts.length > 3 ? Long.parseLong(parts[3]) : -1L;

            return new FileMeta(shardId, firstEventNanos, lastEventNanos);
        }
        catch (final Exception e)
        {
            return new FileMeta(0, 0L, -1L);
        }
    }

    private String getStableKey(final Path path)
    {
        final FileMeta meta = parseMeta(path);
        // By excluding the lastEventNanos from the key, the tailer maintains the
        // same checkpoint when an active file is sealed and renamed with its upper bound.
        return "journal-" + meta.shardId() + "-" + meta.firstEventNanos();
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
        catch (final IOException e)
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
                props.store(out, "R7 Tailer Progress");
            }
            Files.move(tempFile, checkpointPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (final IOException e)
        {
            logger.error("Save failed: {}", e.getMessage());
        }
    }

    private record FileMeta(int shardId, long firstEventNanos, long lastEventNanos)
    {
    }
}