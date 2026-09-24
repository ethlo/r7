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

    private final Path directory;
    private final String filePrefix;
    private final long maxFileSizeBytes;
    private final int zstdLevel;

    private FileChannel channel;
    private OutputStream out;
    private ZstdCompressCtx compressor;
    private Path openPath;
    private Path sealedPath;
    private long bytesWrittenToCurrentFile;
    private String currentWarcinfoId;

    public WarcFileWriter(final Path directory, final String filePrefix, final long maxFileSizeBytes, final int zstdLevel) throws IOException
    {
        if (maxFileSizeBytes <= 0)
        {
            throw new IllegalArgumentException("maxFileSizeBytes must be positive, but was " + maxFileSizeBytes);
        }
        this.directory = directory;
        this.filePrefix = filePrefix;
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.zstdLevel = zstdLevel;
        Files.createDirectories(directory);
        rotate();
    }

    /**
     * Writes a record with a caller-supplied {@code WARC-Record-ID}.
     * <p>
     * The ID is supplied rather than generated here because the records of a four-record
     * exchange group must each carry the <em>others'</em> IDs in {@code WARC-Concurrent-To}
     * before any of them has been written — see {@code WarcExchangeWriter}. Fields are an
     * ordered list rather than a map because {@code WARC-Concurrent-To} may legitimately repeat
     * within one record.
     */
    synchronized void writeRecord(final String recordId, final String warcType, final List<Map.Entry<String, String>> fields, final byte[] block) throws IOException
    {
        if (bytesWrittenToCurrentFile >= maxFileSizeBytes)
        {
            rotate();
        }

        final List<Map.Entry<String, String>> headers = new ArrayList<>(fields.size() + 4);
        headers.add(Map.entry("WARC-Type", warcType));
        headers.add(Map.entry("WARC-Record-ID", "<" + recordId + ">"));
        headers.addAll(fields);
        if (currentWarcinfoId != null && !"warcinfo".equals(warcType))
        {
            headers.add(Map.entry("WARC-Warcinfo-ID", "<" + currentWarcinfoId + ">"));
        }
        headers.add(Map.entry("Content-Length", Long.toString(block.length)));

        writeFrame(headers, block);
    }

    private void writeFrame(final List<Map.Entry<String, String>> headers, final byte[] block) throws IOException
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

        final byte[] frame = compressor.compress(uncompressed);
        out.write(frame);
        out.flush();
        bytesWrittenToCurrentFile += frame.length;
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

        final List<Map.Entry<String, String>> warcinfoFields = new ArrayList<>();
        warcinfoFields.add(Map.entry("WARC-Date", WarcFields.now()));
        warcinfoFields.add(Map.entry("WARC-Filename", fileName));
        warcinfoFields.add(Map.entry("Content-Type", "application/warc-fields"));
        this.currentWarcinfoId = null; // the warcinfo record itself must not carry a Warcinfo-ID
        final String warcinfoId = WarcFields.newRecordId();
        writeRecord(warcinfoId, "warcinfo", warcinfoFields, warcinfoBlock());
        this.currentWarcinfoId = warcinfoId;
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

    @Override
    public synchronized void close() throws IOException
    {
        seal();
    }
}
