package com.ethlo.r7.warc;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.tailer.files.SealedFileWriter;

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
 * Files are written, rolled, sealed and recovered by {@link SealedFileWriter} (see
 * {@code design/warc.md} and {@code design/tailers.md}); this class adds the WARC parts: the
 * {@code warcinfo} record that opens each file, the per-file {@code WARC-X-R7-Sequence}, a
 * complete record being a complete Zstandard frame, and the optional index.
 * <p>
 * <b>CDXJ index</b>, when enabled: each file gets a sorted {@code .cdxj} index next to it (see
 * {@link CdxjIndex}), built from the finished file and sealed the same way, before the WARC file
 * itself is renamed. Wherever a sealed {@code .warc.zst} exists, its index already does.
 */
public final class WarcFileWriter implements AutoCloseable
{
    private static final Logger logger = LoggerFactory.getLogger(WarcFileWriter.class);

    private static final String WARC_SUFFIX = ".warc.zst";
    private static final String INDEX_SUFFIX = ".cdxj";

    private final boolean cdxjIndex;
    private final ZstdCompressCtx compressor;
    private final SealedFileWriter files;

    private long sequence;
    private String currentWarcinfoId;

    /**
     * A record staged in memory, ready to be handed to {@link #writeRecords(List)} as part of a
     * batch that either all lands or none of it does — see {@code WarcExchangeWriter} for why a
     * whole exchange group is written this way instead of record-by-record.
     */
    record PendingRecord(String recordId, String warcType, List<Map.Entry<String, String>> fields, byte[] block)
    {
    }

    public WarcFileWriter(final Path directory, final String filePrefix, final long maxFileSizeBytes, final long maxFileAgeMillis, final int zstdLevel) throws IOException
    {
        this(directory, filePrefix, maxFileSizeBytes, maxFileAgeMillis, zstdLevel, false);
    }

    /**
     * @param cdxjIndex write a CDXJ index next to every file this writer seals
     */
    public WarcFileWriter(final Path directory, final String filePrefix, final long maxFileSizeBytes, final long maxFileAgeMillis, final int zstdLevel,
                          final boolean cdxjIndex) throws IOException
    {
        this.cdxjIndex = cdxjIndex;
        this.compressor = new ZstdCompressCtx();
        compressor.setLevel(zstdLevel);
        compressor.setChecksum(true);
        if (Files.isDirectory(directory))
        {
            removeTornIndexes(directory, filePrefix);
        }
        this.files = new SealedFileWriter(directory, filePrefix, maxFileSizeBytes, maxFileAgeMillis, new WarcFormat());
    }

    /**
     * Seals the current file if it is older than the age limit and holds an exchange. Called
     * from the tailer's loop: a quiet deployment may go arbitrarily long without a write, and
     * size alone would leave one unsealed file for weeks (design/warc.md, "size or age,
     * whichever first").
     */
    public synchronized void rollIfStale() throws IOException
    {
        files.rollIfStale();
    }

    /**
     * Writes a single record with a caller-supplied {@code WARC-Record-ID}. A thin wrapper
     * around {@link #writeRecords(List)} for callers that only ever have one record at a time.
     */
    synchronized void writeRecord(final String recordId, final String warcType, final List<Map.Entry<String, String>> fields, final byte[] block) throws IOException
    {
        writeRecords(List.of(new PendingRecord(recordId, warcType, fields, block)));
    }

    /**
     * Writes a batch of records - e.g. the up-to-four records of one exchange group - as one
     * unit: every frame is built in memory first, so a failure building one (digest
     * computation, field construction) never touches the file, and the frames are then
     * appended in one write. If that write fails partway (a full disk), the file is cut back to
     * where the batch started and keeps every exchange written before it; the sequence numbers
     * the batch used are given back, so the retry continues the file's sequence without a gap.
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

        // Roll before the batch, never inside it: a group stays in one file, and an oversized
        // group simply pushes this file over the limit rather than being split - see
        // design/warc.md ("Roll between records, never inside one"). Age is checked here too,
        // not only between reads: one read of a backlog can outlast max_file_age. Opening the
        // file first writes its warcinfo record and resets the sequence the batch is numbered
        // from.
        files.rollIfFull();
        files.rollIfStale();
        files.ensureOpen();

        final long sequenceBefore = sequence;
        try
        {
            files.append(frames(records));
        }
        catch (final IOException | RuntimeException e)
        {
            sequence = sequenceBefore;
            throw e;
        }
    }

    private byte[] frames(final List<PendingRecord> records)
    {
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
        return combined;
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

    /**
     * The size of the file's first complete frame, or 0 if it has none.
     */
    private static long firstFrameSize(final FileChannel file) throws IOException
    {
        if (file.size() == 0)
        {
            return 0;
        }
        final MappedByteBuffer window = file.map(FileChannel.MapMode.READ_ONLY, 0, Math.min(file.size(), Integer.MAX_VALUE));
        final long frame = Zstd.findFrameCompressedSize(window);
        return Zstd.isError(frame) || frame <= 0 ? 0 : frame;
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
     * An index is rebuilt from its WARC file, so one cut short by a crash is removed before the
     * WARC files left open are sealed (and indexed again).
     */
    private static void removeTornIndexes(final Path directory, final String filePrefix) throws IOException
    {
        final List<Path> torn;
        try (Stream<Path> files = Files.list(directory))
        {
            torn = files.filter(p ->
            {
                final String name = p.getFileName().toString();
                return name.startsWith(filePrefix + "-") && name.endsWith(INDEX_SUFFIX + ".open");
            }).toList();
        }
        for (final Path file : torn)
        {
            Files.delete(file);
        }
    }

    @Override
    public synchronized void close() throws IOException
    {
        try
        {
            files.close();
        }
        finally
        {
            compressor.close();
        }
    }

    private final class WarcFormat implements SealedFileWriter.Format
    {
        @Override
        public String extension()
        {
            return WARC_SUFFIX;
        }

        /**
         * A complete record is a complete Zstandard frame. An exchange group is a single write,
         * so a killed process leaves whole groups; only a torn trailing frame is dropped, and
         * its exchange, never checkpointed, is written again. A file whose only complete frame
         * is its {@code warcinfo} record holds no exchange, and counts as empty.
         */
        @Override
        public long endOfLastCompleteRecord(final FileChannel file) throws IOException
        {
            final long end = endOfLastCompleteFrame(file);
            return end == firstFrameSize(file) ? 0 : end;
        }

        @Override
        public void opened(final SealedFileWriter writer, final String fileName) throws IOException
        {
            sequence = 0;
            currentWarcinfoId = null; // the warcinfo record itself must not carry a Warcinfo-ID
            final List<Map.Entry<String, String>> warcinfoFields = new ArrayList<>();
            warcinfoFields.add(Map.entry("WARC-Date", WarcFields.now()));
            warcinfoFields.add(Map.entry("WARC-Filename", fileName));
            warcinfoFields.add(Map.entry("Content-Type", "application/warc-fields"));
            final String warcinfoId = WarcFields.newRecordId();
            writer.append(buildFrame(warcinfoId, "warcinfo", warcinfoFields, warcinfoBlock()));
            currentWarcinfoId = warcinfoId;
        }

        @Override
        public void sealing(final Path file, final Path sealed) throws IOException
        {
            sealIndex(file, sealed);
        }
    }
}
