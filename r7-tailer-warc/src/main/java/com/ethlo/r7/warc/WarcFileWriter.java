package com.ethlo.r7.warc;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
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

    private final Path directory;
    private final String filePrefix;
    private final long maxFileSizeBytes;
    private final long maxFileAgeMillis;
    private final int zstdLevel;
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
        Files.createDirectories(directory);
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
        long totalBytes = 0;
        for (final PendingRecord record : records)
        {
            final byte[] frame = buildFrame(record.recordId(), record.warcType(), record.fields(), record.block());
            frames.add(frame);
            totalBytes += frame.length;
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
    }

    private byte[] buildFrame(final String recordId, final String warcType, final List<Map.Entry<String, String>> fields, final byte[] block)
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

        return frameBytes(headers, block);
    }

    private byte[] frameBytes(final List<Map.Entry<String, String>> headers, final byte[] block)
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

        return compressor.compress(uncompressed);
    }

    private void rotate() throws IOException
    {
        seal();

        final String fileName = filePrefix + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".warc.zst";
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

    private static byte[] warcinfoBlock()
    {
        final String body = "software: ethlo-r7-tailer-warc\r\n"
                + "format: WARC File Format 1.1\r\n"
                + "conformsTo: https://iipc.github.io/warc-specifications/specifications/warc-format/warc-1.1/\r\n";
        return body.getBytes(StandardCharsets.UTF_8);
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
