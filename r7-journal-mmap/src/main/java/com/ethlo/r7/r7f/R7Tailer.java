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
import com.ethlo.r7.journal.api.JournalIntegrityListener;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.github.luben.zstd.Zstd;

public final class R7Tailer
{
    public static final String COMPRESSED_EXTENSION = R7fConstants.COMPRESSED_FILE_EXTENSION;
    private static final Logger logger = LoggerFactory.getLogger(R7Tailer.class);
    private static final String CHECKPOINT_FILE = ".r7_checkpoints";

    /**
     * Sentinel offset meaning "this segment has been read to the end".
     */
    private static final long FULLY_READ = -1L;

    private final Map<String, Checkpoint> checkpoints = new HashMap<>();
    private final Path logDir;
    private final Duration minAge;
    private final ExchangeReassembler reassembler;
    private final JournalIntegrityListener integrity;
    private final Path checkpointPath;

    private long totalBytesRead = 0;
    private long totalMissingEntries = 0;
    private long totalCorruptEntries = 0;

    // Reusable off-heap buffer
    private ByteBuffer decompressionBuffer = ByteBuffer.allocateDirect(10 * 1024 * 1024);

    public R7Tailer(final Path logDir, final Duration minAge, final ExchangeCompletionListener output)
    {
        this(logDir, minAge, output, JournalIntegrityListener.NOOP, ReassemblyOptions.DEFAULTS);
    }

    /**
     * @param integrity receives damage and loss events for the segments read
     */
    public R7Tailer(final Path logDir,
                    final Duration minAge,
                    final ExchangeCompletionListener output,
                    final JournalIntegrityListener integrity)
    {
        this(logDir, minAge, output, integrity, ReassemblyOptions.DEFAULTS);
    }

    /**
     * @param integrity receives damage and loss events for the segments read
     * @param options   reassembly tuning; see {@code README.md} §11 for the side-effects
     *                  of each setting
     */
    public R7Tailer(final Path logDir,
                    final Duration minAge,
                    final ExchangeCompletionListener output,
                    final JournalIntegrityListener integrity,
                    final ReassemblyOptions options)
    {
        this.logDir = logDir;
        this.minAge = minAge;
        this.reassembler = new ExchangeReassembler(output, options);
        this.integrity = integrity;
        this.checkpointPath = logDir.resolve(CHECKPOINT_FILE);
        loadCheckpoints();
    }

    public long runTick() throws IOException
    {
        totalBytesRead = 0;
        totalMissingEntries = 0;
        totalCorruptEntries = 0;
        final Set<String> fullyProcessedKeys = new HashSet<>();

        try (final Stream<Path> s = Files.list(logDir))
        {
            // Collect and deduplicate files by their stable key
            // This safely resolves an active file rotating to a sealed, then compressed, file
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

                        // Priority: .zst > .r7f > .flux
                        if (existing == null || isHigherPriority(path, existing))
                        {
                            resolvedFiles.put(key, path);
                        }
                    });

            // Sort the resolved files and process them sequentially.
            // Segment sequence is a monotonic counter per shard, so it orders segments
            // exactly; the creation timestamp is wall-clock and cannot be relied on for
            // ordering across a clock step.
            resolvedFiles.values().stream()
                    .sorted((p1, p2) -> {
                        final FileMeta m1 = parseMeta(p1);
                        final FileMeta m2 = parseMeta(p2);
                        if (m1.shardId() != m2.shardId())
                        {
                            return Integer.compare(m1.shardId(), m2.shardId());
                        }
                        return Long.compare(m1.segmentSequence(), m2.segmentSequence());
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

        // The reassembler's age sweep is amortised over incoming events, so on a quiet
        // stream it would never run and abandoned exchanges would be held — and go
        // unreported — until traffic resumed. A tick boundary is a natural pause.
        reassembler.sweep();

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
        final Checkpoint checkpoint = checkpoints.getOrDefault(key, Checkpoint.START);

        // Sentinel check: File is completely read, do not waste CPU decompressing it
        if (checkpoint.offset() == FULLY_READ)
        {
            return true;
        }

        final boolean isCompressed = path.toString().endsWith(COMPRESSED_EXTENSION);
        final boolean isActive = path.toString().endsWith(ACTIVE_FILE_EXTENSION);

        // Enforce the preamble boundary so FlatBuffers never sees the manual binary header
        final long startOffset = Math.max(checkpoint.offset(), R7fConstants.PREAMBLE_SIZE);

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

            // Carry the sequence across ticks: resuming mid-segment without it would let
            // entries go missing across the resume boundary unnoticed.
            final JournalDecoder.DecodeStats stats = JournalDecoder.decode(
                    processingBuffer,
                    reassembler,
                    checkpoint.nextSequence(),
                    path.getFileName().toString(),
                    integrity);

            totalBytesRead += before - processingBuffer.remaining();
            totalMissingEntries += stats.missingEntries();
            totalCorruptEntries += stats.corruptEntriesSkipped();

            if (!stats.isClean())
            {
                logger.error("Segment {} is not intact: {} entries missing, {} corrupt regions skipped ({} bytes).",
                        path.getFileName(), stats.missingEntries(), stats.corruptEntriesSkipped(), stats.bytesSkipped());
            }

            final boolean isFinished = processingBuffer.remaining() == 0 && !isActive;

            final int nextSequence = stats.nextExpectedSequence() == JournalDecoder.UNKNOWN_SEQUENCE
                    ? checkpoint.nextSequence()
                    : stats.nextExpectedSequence();

            if (isFinished)
            {
                checkpoints.put(key, new Checkpoint(FULLY_READ, nextSequence));
            }
            else
            {
                checkpoints.put(key, new Checkpoint(processingBuffer.position(), nextSequence));
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
        if (totalMissingEntries > 0 || totalCorruptEntries > 0)
        {
            logger.error("Tailer Stats: {} entries missing and {} corrupt regions skipped this tick.",
                    totalMissingEntries, totalCorruptEntries);
        }
    }

    public long getMissingEntryCount()
    {
        return totalMissingEntries;
    }

    public long getCorruptEntryCount()
    {
        return totalCorruptEntries;
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

    /**
     * Parses a segment file name.
     * <p>
     * Active segments are named {@code shard-<shardId>-<createdEpochMillis>-<segmentSequence>.flux}.
     * Sealing appends the observed time bounds:
     * {@code shard-<shardId>-<createdEpochMillis>-<segmentSequence>-<firstTs>-<lastTs>.r7f},
     * optionally followed by {@code .zst}. The two trailing fields are therefore only
     * present once a segment has been sealed.
     */
    private FileMeta parseMeta(final Path path)
    {
        final String name = path.getFileName().toString()
                .replace(COMPRESSED_EXTENSION, "")
                .replace(ACTIVE_FILE_EXTENSION, "")
                .replace(R7F_FILE_EXTENSION, "");
        try
        {
            final String[] parts = name.split("-");
            final int shardId = Integer.parseInt(parts[1]);
            final long createdEpochMillis = Long.parseLong(parts[2]);
            final long segmentSequence = Long.parseLong(parts[3]);
            final long firstEventEpochMillis = parts.length > 4 ? Long.parseLong(parts[4]) : -1L;
            final long lastEventEpochMillis = parts.length > 5 ? Long.parseLong(parts[5]) : -1L;

            return new FileMeta(shardId, createdEpochMillis, segmentSequence, firstEventEpochMillis, lastEventEpochMillis);
        }
        catch (final RuntimeException e)
        {
            logger.warn("Unrecognized journal file name: {}", path.getFileName());
            return new FileMeta(0, 0L, 0L, -1L, -1L);
        }
    }

    /**
     * Identifies a segment across its whole life: active, sealed and compressed. The
     * shard id and the monotonic segment sequence are the two fields that never change,
     * so the checkpoint survives both renames.
     */
    private String getStableKey(final Path path)
    {
        final FileMeta meta = parseMeta(path);
        return "journal-" + meta.shardId() + "-" + meta.segmentSequence();
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
            props.forEach((k, v) -> {
                final Checkpoint parsed = Checkpoint.parse((String) v);
                if (parsed != null)
                {
                    checkpoints.put((String) k, parsed);
                }
                else
                {
                    logger.warn("Discarding unparsable checkpoint for {}", k);
                }
            });
            logger.info("Restored {} stable checkpoints from {}", checkpoints.size(), checkpointPath.toAbsolutePath());
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
            checkpoints.forEach((k, v) -> props.setProperty(k, v.serialize()));

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

    /**
     * How far into a segment the tailer has read, and which entry sequence it expects
     * next. Stored as {@code offset:sequence}; a bare offset is accepted so checkpoints
     * written before sequence tracking still load.
     */
    private record Checkpoint(long offset, int nextSequence)
    {
        static final Checkpoint START = new Checkpoint(0L, JournalDecoder.UNKNOWN_SEQUENCE);

        static Checkpoint parse(final String value)
        {
            try
            {
                final int sep = value.indexOf(':');
                if (sep < 0)
                {
                    return new Checkpoint(Long.parseLong(value.trim()), JournalDecoder.UNKNOWN_SEQUENCE);
                }
                return new Checkpoint(
                        Long.parseLong(value.substring(0, sep).trim()),
                        Integer.parseInt(value.substring(sep + 1).trim()));
            }
            catch (final NumberFormatException e)
            {
                return null;
            }
        }

        String serialize()
        {
            return offset + ":" + nextSequence;
        }
    }

    private record FileMeta(int shardId,
                            long createdEpochMillis,
                            long segmentSequence,
                            long firstEventEpochMillis,
                            long lastEventEpochMillis)
    {
    }
}
