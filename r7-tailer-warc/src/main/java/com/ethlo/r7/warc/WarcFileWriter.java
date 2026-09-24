package com.ethlo.r7.warc;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
 */
public final class WarcFileWriter implements AutoCloseable
{
    private static final Logger logger = LoggerFactory.getLogger(WarcFileWriter.class);

    private final Path directory;
    private final String filePrefix;
    private final long maxFileSizeBytes;
    private final int zstdLevel;

    private OutputStream out;
    private ZstdCompressCtx compressor;
    private Path currentPath;
    private long bytesWrittenToCurrentFile;
    private String currentWarcinfoId;

    public WarcFileWriter(final Path directory, final String filePrefix, final long maxFileSizeBytes, final int zstdLevel) throws IOException
    {
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
     * The ID is supplied rather than generated here because a request/response pair must each
     * carry the <em>other's</em> ID in {@code WARC-Concurrent-To} before either has been
     * written — see {@code WarcExchangeWriter}.
     */
    synchronized void writeRecord(final String recordId, final String warcType, final Map<String, String> fields, final byte[] block) throws IOException
    {
        if (bytesWrittenToCurrentFile >= maxFileSizeBytes)
        {
            rotate();
        }

        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("WARC-Type", warcType);
        headers.put("WARC-Record-ID", "<" + recordId + ">");
        headers.putAll(fields);
        if (currentWarcinfoId != null && !"warcinfo".equals(warcType))
        {
            headers.put("WARC-Warcinfo-ID", "<" + currentWarcinfoId + ">");
        }
        headers.put("Content-Length", Long.toString(block.length));

        writeFrame(headers, block);
    }

    private void writeFrame(final Map<String, String> headers, final byte[] block) throws IOException
    {
        final StringBuilder sb = new StringBuilder(256);
        sb.append("WARC/1.1\r\n");
        for (final Map.Entry<String, String> e : headers.entrySet())
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
        close();

        final String fileName = filePrefix + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".warc.zst";
        this.currentPath = directory.resolve(fileName);
        this.out = new BufferedOutputStream(Files.newOutputStream(currentPath));
        this.compressor = new ZstdCompressCtx();
        compressor.setLevel(zstdLevel);
        compressor.setChecksum(true);
        this.bytesWrittenToCurrentFile = 0;

        final Map<String, String> warcinfoFields = new LinkedHashMap<>();
        warcinfoFields.put("WARC-Date", WarcFields.now());
        warcinfoFields.put("WARC-Filename", fileName);
        warcinfoFields.put("Content-Type", "application/warc-fields");
        this.currentWarcinfoId = null; // the warcinfo record itself must not carry a Warcinfo-ID
        final String warcinfoId = WarcFields.newRecordId();
        writeRecord(warcinfoId, "warcinfo", warcinfoFields, warcinfoBlock());
        this.currentWarcinfoId = warcinfoId;
        logger.info("Rotated to new WARC file: {}", currentPath);
    }

    private static byte[] warcinfoBlock()
    {
        final String body = "software: ethlo-r7-tailer-warc\r\n"
                + "format: WARC File Format 1.1\r\n"
                + "conformsTo: https://iipc.github.io/warc-specifications/specifications/warc-format/warc-1.1/\r\n";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public synchronized void close() throws IOException
    {
        if (compressor != null)
        {
            compressor.close();
            compressor = null;
        }
        if (out != null)
        {
            out.close();
            out = null;
        }
    }
}
