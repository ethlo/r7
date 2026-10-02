package com.ethlo.r7.warc;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.netpreserve.jwarc.MediaType;
import org.netpreserve.jwarc.URIs;
import org.netpreserve.jwarc.WarcCaptureRecord;
import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcRecord;
import org.netpreserve.jwarc.WarcRequest;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.jwarc.WarcRevisit;
import org.netpreserve.jwarc.cdx.CdxFormat;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdInputStream;

/**
 * Builds the CDXJ index of one finished WARC file: one line per exchange, sorted, in the
 * format pywb and OutbackCDX read ({@code <surt key> <14-digit timestamp> {json}}), each
 * pointing at the client response record by file name, offset and length.
 * <p>
 * The index is derived from the file itself, never from what the writer remembers it wrote, so
 * a file sealed after a crash gets the same index as one sealed normally.
 * <p>
 * Only the client response of each exchange is indexed (a {@code response} record, or a
 * {@code revisit} when its payload was deduplicated): that is what the client got back, and
 * what a replay tool should serve. The upstream response of a proxied exchange is the record
 * directly before it, listed in its {@code WARC-Concurrent-To}; it carries no payload of its
 * own and is skipped. A non-GET exchange gets {@code __wb_method=<method>} in its key, the
 * pywb convention that keeps a {@code DELETE} and a {@code GET} of one URL apart. The request
 * body is deliberately left out of the key: an index is copied to more places than the
 * archive, and must not carry request content.
 */
public final class CdxjIndex
{
    private CdxjIndex()
    {
    }

    /**
     * Indexes {@code warcFile} into {@code indexFile}, which is created or replaced, and
     * fsync'd before this returns.
     *
     * @param filename the name the index records for the WARC file, its final sealed name
     */
    public static void write(final Path warcFile, final String filename, final Path indexFile) throws IOException
    {
        final List<String> lines = lines(warcFile, filename);
        lines.sort(null);
        try (FileChannel channel = FileChannel.open(indexFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))
        {
            final Writer out = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8));
            for (final String line : lines)
            {
                out.write(line);
                out.write('\n');
            }
            out.flush();
            channel.force(true);
        }
    }

    static List<String> lines(final Path warcFile, final String filename) throws IOException
    {
        final List<String> lines = new ArrayList<>();
        final RecordVisitor visitor = new RecordVisitor(filename, lines::add);
        try (FileChannel file = FileChannel.open(warcFile, StandardOpenOption.READ))
        {
            // One record per Zstandard frame (see WarcFileWriter), so a record's offset and
            // length are its frame's. Frames are walked through a mapped window, remapped from
            // a frame's start when it runs past the window, as WarcFileWriter does on recovery.
            final long size = file.size();
            long windowStart = 0;
            while (windowStart < size)
            {
                final int windowSize = (int) Math.min(size - windowStart, Integer.MAX_VALUE);
                final MappedByteBuffer window = file.map(FileChannel.MapMode.READ_ONLY, windowStart, windowSize);
                int within = 0;
                while (within < windowSize)
                {
                    final long frameSize = Zstd.findFrameCompressedSize(window.slice(within, windowSize - within));
                    if (Zstd.isError(frameSize) || frameSize <= 0)
                    {
                        break;
                    }
                    try (InputStream record = new ZstdInputStream(new ByteBufferInputStream(window.slice(within, (int) frameSize))))
                    {
                        visitor.visit(windowStart + within, frameSize, record);
                    }
                    within += (int) frameSize;
                }
                if (within == 0)
                {
                    throw new IOException("No complete Zstandard frame at offset " + windowStart + " of " + warcFile);
                }
                windowStart += within;
            }
        }
        visitor.finish();
        return lines;
    }

    /**
     * The index lines for one batch of records about to be appended to a WARC file, as
     * {@link #write} will later find them in the finished file. A batch is written whole (see
     * {@link WarcFileWriter#writeRecords}), and an exchange is never split across batches.
     *
     * @param filename the final name of the WARC file the batch goes into
     * @param offset   where the batch's first record will start in that file
     * @param records  each record's uncompressed bytes and its compressed (frame) length
     */
    static List<String> linesFor(final String filename, final long offset, final List<Map.Entry<byte[], Long>> records) throws IOException
    {
        final List<String> lines = new ArrayList<>(1);
        final RecordVisitor visitor = new RecordVisitor(filename, lines::add);
        long position = offset;
        for (final Map.Entry<byte[], Long> record : records)
        {
            visitor.visit(position, record.getValue(), new ByteArrayInputStream(record.getKey()));
            position += record.getValue();
        }
        visitor.finish();
        return lines;
    }

    /**
     * Sees the records in file order and decides each response once the record after it is
     * known.
     */
    private static final class RecordVisitor
    {
        private final String filename;
        private final Consumer<String> lines;

        private String clientRequestExchange;
        private URI clientRequestId;
        private String clientRequestMethod;

        private WarcCaptureRecord pending;
        private long pendingOffset;
        private long pendingLength;
        private String pendingMethod;

        RecordVisitor(final String filename, final Consumer<String> lines)
        {
            this.filename = filename;
            this.lines = lines;
        }

        void visit(final long offset, final long length, final InputStream uncompressed) throws IOException
        {
            try (WarcReader reader = new WarcReader(uncompressed))
            {
                final WarcRecord record = reader.next().orElseThrow(() -> new IOException("Empty WARC record at offset " + offset));
                if (pending != null && !isClientResponseOf(record, pending))
                {
                    lines.accept(line(pending, filename, pendingOffset, pendingLength, pendingMethod));
                }
                pending = null;

                if (record instanceof WarcRequest request)
                {
                    // The first request record of an exchange is the client's; a second one is
                    // the forwarded request to the upstream.
                    final String exchange = request.headers().first("WARC-R7-Request-Id").orElse(null);
                    if (exchange == null || !exchange.equals(clientRequestExchange))
                    {
                        clientRequestExchange = exchange;
                        clientRequestId = request.id();
                        clientRequestMethod = request.http().method();
                    }
                }
                else if (record instanceof WarcResponse || record instanceof WarcRevisit)
                {
                    final WarcCaptureRecord capture = (WarcCaptureRecord) record;
                    // Status and mime come from the HTTP header block: parse it while the
                    // record is still open.
                    if (capture.contentType().base().equals(MediaType.HTTP))
                    {
                        if (capture instanceof WarcResponse response)
                        {
                            response.http();
                        }
                        else
                        {
                            ((WarcRevisit) capture).http();
                        }
                    }
                    pending = capture;
                    pendingOffset = offset;
                    pendingLength = length;
                    pendingMethod = capture.concurrentTo().contains(clientRequestId) ? clientRequestMethod : null;
                }
            }
        }

        void finish()
        {
            if (pending != null)
            {
                lines.accept(line(pending, filename, pendingOffset, pendingLength, pendingMethod));
                pending = null;
            }
        }
    }

    /**
     * True when {@code next} is the client response written directly after the upstream
     * response {@code upstream}, naming it in {@code WARC-Concurrent-To}.
     */
    private static boolean isClientResponseOf(final WarcRecord next, final WarcCaptureRecord upstream)
    {
        return (next instanceof WarcResponse || next instanceof WarcRevisit)
                && ((WarcCaptureRecord) next).concurrentTo().contains(upstream.id());
    }

    /**
     * A CDXJ line as pywb writes it, plus {@code request_id}: the r7 request id the JSON tailer
     * and the journal use, so an index line joins to the rest of its exchange. pywb and
     * OutbackCDX ignore fields they do not know.
     */
    private static String line(final WarcCaptureRecord capture, final String filename, final long offset, final long length, final String method)
    {
        final String line = CdxFormat.CDXJ.format(capture, filename, offset, length, urlKey(capture, method));
        final String requestId = capture.headers().first("WARC-R7-Request-Id").orElse(null);
        if (requestId == null || !line.endsWith("}"))
        {
            return line;
        }
        return line.substring(0, line.length() - 1) + ", \"request_id\": " + jsonString(requestId) + "}";
    }

    private static String jsonString(final String value)
    {
        final StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++)
        {
            final char c = value.charAt(i);
            if (c == '"' || c == '\\')
            {
                sb.append('\\').append(c);
            }
            else if (c < 0x20)
            {
                sb.append(String.format("\\u%04x", (int) c));
            }
            else
            {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String urlKey(final WarcCaptureRecord capture, final String method)
    {
        if (method == null || method.equals("GET"))
        {
            // CdxFormat derives the key from the target URI itself.
            return null;
        }
        final String target = capture.target();
        return URIs.toNormalizedSurt(target + (target.contains("?") ? '&' : '?') + "__wb_method=" + method);
    }

    private static final class ByteBufferInputStream extends InputStream
    {
        private final ByteBuffer buffer;

        ByteBufferInputStream(final ByteBuffer buffer)
        {
            this.buffer = buffer;
        }

        @Override
        public int read()
        {
            return buffer.hasRemaining() ? buffer.get() & 0xFF : -1;
        }

        @Override
        public int read(final byte[] b, final int off, final int len)
        {
            if (len == 0)
            {
                return 0;
            }
            if (!buffer.hasRemaining())
            {
                return -1;
            }
            final int n = Math.min(len, buffer.remaining());
            buffer.get(b, off, n);
            return n;
        }
    }
}
