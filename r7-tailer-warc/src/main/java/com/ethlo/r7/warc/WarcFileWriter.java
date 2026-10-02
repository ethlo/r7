package com.ethlo.r7.warc;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes WARC 1.1 records to a rotating sequence of {@code .warc.zst} files.
 * <p>
 * Compression follows the (proposed) IIPC "Zstandard Compression for WARC Files 1.0" spec: each
 * WARC record is compressed as exactly one independent, self-contained Zstandard frame (content
 * size and checksum included), and frames are simply concatenated — the same "record-at-a-time"
 * convention {@code .warc.gz} has used for gzip since WARC/1.0, carried over to zstd. This keeps
 * two properties gzip WARC readers already rely on: a tool can decompress-and-concatenate the
 * whole file to recover a plain WARC file, and — given an external index of frame offsets — a
 * single record can be located and decompressed without touching the rest of the file.
 * <p>
 * <b>Open/sealed lifecycle</b>, the same protocol the journal uses for {@code .flux} -&gt;
 * {@code .r7f} (see {@code design/warc.md}): a file is written under a {@code .open} suffix and
 * only fsync'd and atomically renamed to its final {@code .warc.zst} name once nothing more will
 * be written to it (on rotation, or on {@link #close()}). Without this, a consumer watching the
 * output directory for finished files could see a "final" name while the last frame was still
 * buffered, and a crash mid-write would leave a truncated file with no way to tell it apart from
 * one that finished cleanly.
 * <p>
 * <b>CDXJ index</b>, when enabled: each file gets a sorted {@code .cdxj} index next to it (see
 * {@link CdxjIndex}), built from the finished file and sealed the same way, before the WARC file
 * itself is renamed. Wherever a sealed {@code .warc.zst} exists, its index already does.
 */
public final class WarcFileWriter implements AutoCloseable
{
    private static final Logger logger = LoggerFactory.getLogger(WarcFileWriter.class);

    /**
     * Same floor and the same reason as {@code R7fJournalProvider.MIN_SEGMENT_SIZE}: a rollover
     * size that cannot hold a single record is a livelock waiting to be discovered in
     * production, so it is refused at construction instead.
     */
    public static final long MIN_ROLLOVER_SIZE = 64L * 1024L;

    private static final String WARC_SUFFIX = ".warc.zst";
    private static final String INDEX_SUFFIX = ".cdxj";

    private final Path directory;
    private final String filePrefix;
    private final long maxFileSizeBytes;
    private final long maxFileAgeMillis;
    private final int zstdLevel;
    private final boolean cdxjIndex;
    private final Consumer<String> indexStream;
    private final ScheduledExecutorService rotationScheduler;

    private FileChannel channel;
    private OutputStream out;
    private ZstdCompressCtx compressor;
    private Path openPath;
    private Path sealedPath;
    private long bytesWrittenToCurrentFile;
    private long sequence;
    private volatile long fileOpenedAtMillis;
    private volatile boolean hasRecordsSinceRotate;
    private String currentWarcinfoId;

    /**
     * A record staged in memory, ready to be handed to {@link #writeRecords(List)} as part of a
     * batch that either all lands durably or none of it does — see {@code WarcExchangeWriter}
     * for why a whole exchange group is written this way instead of record-by-record.
     */
    record PendingRecord(String recordId, String warcType, List<Map.Entry<String, String>> fields, byte[] block)
    {
    }

    public WarcFileWriter(final Path directory, final String filePrefix, final long maxFileSizeBytes, final long maxFileAgeMillis, final int zstdLevel) throws IOException
    {
        this(directory, filePrefix, maxFileSizeBytes, maxFileAgeMillis, zstdLevel, false, null);
    }

    /**
     * @param cdxjIndex   write a CDXJ index next to every file this writer seals
     * @param indexStream receives each exchange's CDXJ line as soon as its records are written,
     *                    or {@code null} for none
     */
    public WarcFileWriter(final Path directory, final String filePrefix, final long maxFileSizeBytes, final long maxFileAgeMillis, final int zstdLevel,
                          final boolean cdxjIndex, final Consumer<String> indexStream) throws IOException
    {
        if (maxFileSizeBytes < MIN_ROLLOVER_SIZE)
        {
            throw new IllegalArgumentException("maxFileSizeBytes must be at least " + MIN_ROLLOVER_SIZE + ", but was " + maxFileSizeBytes);
        }
        if (maxFileAgeMillis <= 0)
        {
            throw new IllegalArgumentException("maxFileAgeMillis must be positive, but was " + maxFileAgeMillis);
        }
        this.directory = directory;
        this.filePrefix = filePrefix;
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.maxFileAgeMillis = maxFileAgeMillis;
        this.zstdLevel = zstdLevel;
        this.cdxjIndex = cdxjIndex;
        this.indexStream = indexStream;
        Files.createDirectories(directory);
        sealLeftovers();
        rotate();

        // Age-based rollover has to run on its own clock: a quiet deployment may go arbitrarily
        // long without a single write, and nothing on the write path would ever notice the file
        // has gone stale. design/warc.md calls this "size or age, whichever first" for exactly
        // that reason - size alone leaves one unsealed file for weeks under light traffic.
        final long checkIntervalMillis = Math.max(1000L, Math.min(maxFileAgeMillis / 4, 30_000L));
        this.rotationScheduler = Executors.newSingleThreadScheduledExecutor(r ->
        {
            final Thread t = new Thread(r, "warc-age-rollover");
            t.setDaemon(true);
            return t;
        });
        rotationScheduler.scheduleWithFixedDelay(this::rotateIfStale, checkIntervalMillis, checkIntervalMillis, TimeUnit.MILLISECONDS);
    }

    private synchronized void rotateIfStale()
    {
        try
        {
            // Only a file that has actually received exchange records is worth sealing early -
            // an idle deployment with genuinely zero traffic has nothing to protect, and would
            // otherwise churn out an unbounded number of warcinfo-only files.
            if (hasRecordsSinceRotate && System.currentTimeMillis() - fileOpenedAtMillis >= maxFileAgeMillis)
            {
                rotate();
            }
        }
        catch (final IOException e)
        {
            logger.warn("Failed to age-rotate current WARC file", e);
        }
    }

    /**
     * Writes a single record with a caller-supplied {@code WARC-Record-ID}. A thin wrapper
     * around {@link #writeRecords(List)} for callers - such as the {@code warcinfo} record
     * written by {@link #rotate()} - that only ever have one record at a time.
     */
    synchronized void writeRecord(final String recordId, final String warcType, final List<Map.Entry<String, String>> fields, final byte[] block) throws IOException
    {
        writeRecords(List.of(new PendingRecord(recordId, warcType, fields, block)));
    }

    /**
     * Writes a batch of records - e.g. the up-to-four records of one exchange group - as a
     * single durable unit: every frame in the batch is built in memory first (pure computation,
     * nothing written yet), then appended to the file in one {@code write} call and fsync'd via
     * the normal flush path. Building the frames first keeps every business-logic failure
     * (digest computation, field construction) from ever touching disk, which is the common
     * case; the underlying {@code write} call itself is not transactional, though — a
     * {@link BufferedOutputStream} can still copy part of {@code combined} before an
     * {@link IOException} (a full disk partway through). Since a torn write can leave a
     * corrupt trailing frame that a retry would otherwise write past, any failure here discards
     * the entire current {@code .open} file rather than trying to salvage the good prefix: it
     * was never sealed, so nothing has been exposed to a reader as durable yet, and the next
     * call opens a fresh, empty file for the retried batch to land in cleanly. See
     * {@link #discardCurrentFile()}.
     * <p>
     * The IDs are supplied rather than generated here because the records of a four-record
     * exchange group must each carry the <em>others'</em> IDs in {@code WARC-Concurrent-To}
     * before any of them has been written — see {@code WarcExchangeWriter}. Fields are an
     * ordered list rather than a map because {@code WARC-Concurrent-To} may legitimately repeat
     * within one record.
     */
    synchronized void writeRecords(final List<PendingRecord> records) throws IOException
    {
        if (records.isEmpty())
        {
            return;
        }

        // A prior failure may have discarded the file without opening a replacement (to avoid
        // recursing back into this same failure path) - open one now if so.
        if (out == null)
        {
            rotate();
        }

        // Roll before the batch, never inside it: a group stays in one file, and an oversized
        // group simply pushes this file over the limit rather than being split - see
        // design/warc.md ("Roll between records, never inside one").
        if (bytesWrittenToCurrentFile > 0 && bytesWrittenToCurrentFile >= maxFileSizeBytes)
        {
            rotate();
        }

        final List<byte[]> frames = new ArrayList<>(records.size());
        final List<Map.Entry<byte[], Long>> indexed = indexStream != null ? new ArrayList<>(records.size()) : null;
        final List<String> indexLines;
        long totalBytes = 0;
        final long sequenceBefore = sequence;
        try
        {
            for (final PendingRecord record : records)
            {
                final byte[] uncompressed = buildRecord(record.recordId(), record.warcType(), record.fields(), record.block());
                final byte[] frame = compressor.compress(uncompressed);
                frames.add(frame);
                if (indexed != null)
                {
                    indexed.add(Map.entry(uncompressed, (long) frame.length));
                }
                totalBytes += frame.length;
            }
            // Built before the write, like the frames: once the batch is on disk nothing may
            // throw, or the tailer would retry the exchange and write it twice.
            indexLines = indexed != null
                    ? CdxjIndex.linesFor(sealedPath.getFileName().toString(), bytesWrittenToCurrentFile, indexed)
                    : List.of();
        }
        catch (final IOException | RuntimeException e)
        {
            // Nothing was written: hand the sequence numbers back, or the retry would leave a
            // gap that reads as lost records.
            sequence = sequenceBefore;
            throw e;
        }

        final byte[] combined = new byte[Math.toIntExact(totalBytes)];
        int offset = 0;
        for (final byte[] frame : frames)
        {
            System.arraycopy(frame, 0, combined, offset, frame.length);
            offset += frame.length;
        }

        try
        {
            out.write(combined);
            out.flush();
        }
        catch (final IOException e)
        {
            discardCurrentFile();
            throw e;
        }
        bytesWrittenToCurrentFile += totalBytes;
        hasRecordsSinceRotate = true;
        for (final String line : indexLines)
        {
            indexStream.accept(line);
        }
    }

    private byte[] buildRecord(final String recordId, final String warcType, final List<Map.Entry<String, String>> fields, final byte[] block)
    {
        final List<Map.Entry<String, String>> headers = new ArrayList<>(fields.size() + 5);
        headers.add(Map.entry("WARC-Type", warcType));
        headers.add(Map.entry("WARC-Record-ID", "<" + recordId + ">"));
        headers.addAll(fields);
        if (currentWarcinfoId != null && !"warcinfo".equals(warcType))
        {
            headers.add(Map.entry("WARC-Warcinfo-ID", "<" + currentWarcinfoId + ">"));
        }
        // Per-file, monotonically increasing: without it a file that loses a page in the middle
        // reads back as a shorter, wholly self-consistent file - see design/warc.md's "Three
        // things must come along" for why this is the one thing nothing else in the format
        // detects.
        headers.add(Map.entry("WARC-X-R7-Sequence", Long.toString(sequence++)));
        headers.add(Map.entry("Content-Length", Long.toString(block.length)));

        return recordBytes(headers, block);
    }

    private static byte[] recordBytes(final List<Map.Entry<String, String>> headers, final byte[] block)
    {
        final StringBuilder sb = new StringBuilder(256);
        sb.append("WARC/1.1\r\n");
        for (final Map.Entry<String, String> e : headers)
        {
            if (e.getValue() != null)
            {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        sb.append("\r\n");

        final byte[] headerBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        final byte[] uncompressed = new byte[headerBytes.length + block.length + 4];
        System.arraycopy(headerBytes, 0, uncompressed, 0, headerBytes.length);
        System.arraycopy(block, 0, uncompressed, headerBytes.length, block.length);
        // Two CRLFs terminate every WARC record, uncompressed-file style — see class javadoc.
        uncompressed[uncompressed.length - 4] = '\r';
        uncompressed[uncompressed.length - 3] = '\n';
        uncompressed[uncompressed.length - 2] = '\r';
        uncompressed[uncompressed.length - 1] = '\n';
        return uncompressed;
    }

    private void rotate() throws IOException
    {
        seal();

        final String fileName = filePrefix + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + WARC_SUFFIX;
        this.sealedPath = directory.resolve(fileName);
        this.openPath = directory.resolve(fileName + ".open");
        this.channel = FileChannel.open(openPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        this.out = new BufferedOutputStream(Channels.newOutputStream(channel));
        this.compressor = new ZstdCompressCtx();
        compressor.setLevel(zstdLevel);
        compressor.setChecksum(true);
        this.bytesWrittenToCurrentFile = 0;
        this.sequence = 0;
        this.fileOpenedAtMillis = System.currentTimeMillis();

        final List<Map.Entry<String, String>> warcinfoFields = new ArrayList<>();
        warcinfoFields.add(Map.entry("WARC-Date", WarcFields.now()));
        warcinfoFields.add(Map.entry("WARC-Filename", fileName));
        warcinfoFields.add(Map.entry("Content-Type", "application/warc-fields"));
        this.currentWarcinfoId = null; // the warcinfo record itself must not carry a Warcinfo-ID
        final String warcinfoId = WarcFields.newRecordId();
        writeRecord(warcinfoId, "warcinfo", warcinfoFields, warcinfoBlock());
        this.currentWarcinfoId = warcinfoId;
        this.hasRecordsSinceRotate = false; // the warcinfo record itself doesn't count as traffic
        logger.info("Rotated to new WARC file: {}", openPath);
    }

    /**
     * Seals the files a crash left under their {@code .open} name.
     * <p>
     * Every exchange in such a file reached the OS before the tailer checkpointed past it, so
     * the tailer will not write it again: left unsealed, it is invisible to every consumer that
     * picks up finished files by name, which is all of them. Each file is cut back to its last
     * complete Zstandard frame and sealed. An exchange group is a single {@code write}, so a
     * killed process leaves whole groups; only a torn trailing frame is dropped, and its
     * exchange, never checkpointed, is written again.
     */
    private void sealLeftovers() throws IOException
    {
        final List<Path> ownFiles;
        try (Stream<Path> files = Files.list(directory))
        {
            ownFiles = files.filter(p -> p.getFileName().toString().startsWith(filePrefix + "-")).toList();
        }
        final List<Path> leftovers = new ArrayList<>();
        for (final Path file : ownFiles)
        {
            final String name = file.getFileName().toString();
            if (name.endsWith(WARC_SUFFIX + ".open"))
            {
                leftovers.add(file);
            }
            else if (name.endsWith(INDEX_SUFFIX + ".open"))
            {
                // An index is rebuilt from its WARC file, so one cut short by a crash is removed.
                Files.delete(file);
            }
        }
        for (final Path leftover : leftovers)
        {
            final long complete;
            try (FileChannel file = FileChannel.open(leftover, StandardOpenOption.READ, StandardOpenOption.WRITE))
            {
                complete = endOfLastCompleteFrame(file);
                if (complete < file.size())
                {
                    logger.warn("Cut a torn trailing frame ({} bytes) from {}; its exchange will be written again",
                            file.size() - complete, leftover.getFileName());
                    file.truncate(complete);
                }
                file.force(true);
            }
            if (complete == 0)
            {
                Files.delete(leftover);
                continue;
            }
            final String name = leftover.getFileName().toString();
            final Path sealed = leftover.resolveSibling(name.substring(0, name.length() - ".open".length()));
            sealIndex(leftover, sealed);
            Files.move(leftover, sealed, StandardCopyOption.ATOMIC_MOVE);
            logger.info("Sealed WARC file left open by an earlier run: {}", sealed);
        }
    }

    /**
     * Walks the file frame by frame through a mapped window (a file may exceed what one
     * mapping can hold), remapping from a frame's start when it runs past the window.
     */
    private static long endOfLastCompleteFrame(final FileChannel file) throws IOException
    {
        final long size = file.size();
        long end = 0;
        while (end < size)
        {
            final long windowSize = Math.min(size - end, Integer.MAX_VALUE);
            final MappedByteBuffer window = file.map(FileChannel.MapMode.READ_ONLY, end, windowSize);
            long within = 0;
            while (within < windowSize)
            {
                final long frame = Zstd.findFrameCompressedSize(window.slice((int) within, (int) (windowSize - within)));
                if (Zstd.isError(frame) || frame <= 0)
                {
                    break;
                }
                within += frame;
            }
            if (within == 0 || end + windowSize == size)
            {
                // No complete frame from here, or the window reached the end of the file.
                return end + within;
            }
            end += within;
        }
        return end;
    }

    private static byte[] warcinfoBlock()
    {
        final String body = "software: ethlo-r7-tailer-warc\r\n"
                + "format: WARC File Format 1.1\r\n"
                + "conformsTo: https://iipc.github.io/warc-specifications/specifications/warc-format/warc-1.1/\r\n";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Writes the CDXJ index of a finished WARC file under an {@code .open} name and renames it
     * to {@code <name>.cdxj}, replacing any index an interrupted earlier seal left. A no-op when
     * indexing is off.
     */
    private void sealIndex(final Path warcFile, final Path sealedWarc) throws IOException
    {
        if (!cdxjIndex)
        {
            return;
        }
        final String warcName = sealedWarc.getFileName().toString();
        final String baseName = warcName.substring(0, warcName.length() - WARC_SUFFIX.length());
        final Path openIndex = sealedWarc.resolveSibling(baseName + INDEX_SUFFIX + ".open");
        final Path index = sealedWarc.resolveSibling(baseName + INDEX_SUFFIX);
        CdxjIndex.write(warcFile, warcName, openIndex);
        Files.move(openIndex, index, StandardCopyOption.ATOMIC_MOVE);
        logger.info("Sealed CDXJ index: {}", index);
    }

    /**
     * Flushes, fsyncs and atomically renames the current file from its {@code .open} name to
     * its final {@code .warc.zst} name, so a consumer watching the directory never observes a
     * final name before every byte behind it is durable. A no-op if nothing is currently open.
     */
    private void seal() throws IOException
    {
        if (compressor != null)
        {
            compressor.close();
            compressor = null;
        }
        if (out != null)
        {
            out.flush();
            channel.force(true);
            out.close();
            out = null;
            channel = null;
        }
        if (openPath != null)
        {
            // A failure here leaves the file open and is retried by the next seal.
            sealIndex(openPath, sealedPath);
            Files.move(openPath, sealedPath, StandardCopyOption.ATOMIC_MOVE);
            logger.info("Sealed WARC file: {}", sealedPath);
            openPath = null;
            sealedPath = null;
        }
    }

    /**
     * Closes and deletes the current {@code .open} file outright, without sealing it - the
     * response to a write failing partway through {@link #writeRecords}, where the file may now
     * hold a truncated trailing frame. Every record written to this file so far, including any
     * earlier successful exchange groups, is discarded along with it: none of it was ever
     * sealed, so none of it was durable or exposed to a reader yet, and salvaging "the good
     * prefix" of a stream whose exact failure point is not reliably knowable (a
     * {@link BufferedOutputStream} does not report how many bytes of a failed {@code write}
     * actually reached the channel) risks leaving a corrupt frame in the middle of a file that
     * later gets sealed. Leaves the writer with no open file - the next {@link #writeRecords}
     * call opens a fresh one.
     */
    private void discardCurrentFile()
    {
        if (compressor != null)
        {
            try
            {
                compressor.close();
            }
            catch (final RuntimeException e)
            {
                logger.warn("Failed to close the Zstandard context of a discarded WARC file", e);
            }
            compressor = null;
        }
        if (out != null)
        {
            try
            {
                out.close();
            }
            catch (final IOException e)
            {
                logger.warn("Failed to close a discarded WARC file's stream", e);
            }
            out = null;
            channel = null;
        }
        if (openPath != null)
        {
            try
            {
                Files.deleteIfExists(openPath);
            }
            catch (final IOException e)
            {
                logger.warn("Failed to delete WARC file '{}' after a partial write - it may contain a truncated frame", openPath, e);
            }
            logger.warn("Discarded WARC file after a partial/failed write: {}", openPath);
        }
        openPath = null;
        sealedPath = null;
    }

    @Override
    public synchronized void close() throws IOException
    {
        rotationScheduler.shutdownNow();
        seal();
    }
}
