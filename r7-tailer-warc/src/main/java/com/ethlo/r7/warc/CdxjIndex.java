package com.ethlo.r7.warc;

import java.io.BufferedWriter;
import java.io.IOException;
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
import java.util.Set;

import org.netpreserve.jwarc.MediaType;
import org.netpreserve.jwarc.URIs;
import org.netpreserve.jwarc.WarcCaptureRecord;
import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcRecord;
import org.netpreserve.jwarc.WarcRequest;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.jwarc.WarcRevisit;
import org.netpreserve.jwarc.cdx.CdxFormat;

import com.ethlo.r7.r7f.JournalFiles;
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
     * Indexes {@code warcFile} into {@code indexFile}, which is created (with a journal
     * segment's permissions) or replaced, and fsync'd before this returns.
     *
     * @param filename the name the index records for the WARC file, its final sealed name
     */
    public static void write(final Path warcFile, final String filename, final Path indexFile) throws IOException
    {
        final List<String> lines = lines(warcFile, filename);
        lines.sort(null);
        // The index names every URL in the archive, so it gets a journal segment's permissions,
        // as the WARC file does.
        final Path directory = indexFile.toAbsolutePath().getParent();
        try (FileChannel channel = FileChannel.open(indexFile,
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
                JournalFiles.fileAttributes(directory)))
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
        final RecordVisitor visitor = new RecordVisitor(filename, lines);
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
                    visitor.visit(windowStart + within, frameSize, window.slice(within, (int) frameSize));
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
     * Sees the records in file order and decides each response once the record after it is
     * known.
     */
    private static final class RecordVisitor
    {
        private final String filename;
        private final List<String> lines;

        private String clientRequestExchange;
        private URI clientRequestId;
        private String clientRequestMethod;

        private WarcCaptureRecord pending;
        private long pendingOffset;
        private long pendingLength;
        private String pendingMethod;

        RecordVisitor(final String filename, final List<String> lines)
        {
            this.filename = filename;
            this.lines = lines;
        }

        void visit(final long offset, final long length, final ByteBuffer frame) throws IOException
        {
            try (WarcReader reader = new WarcReader(new ZstdInputStream(new ByteBufferInputStream(frame))))
            {
                final WarcRecord record = reader.next().orElseThrow(() -> new IOException("Empty WARC record at offset " + offset));
                if (pending != null && !isClientResponseOf(record, pending))
                {
                    lines.add(CdxFormat.CDXJ.format(pending, filename, pendingOffset, pendingLength, urlKey(pending, pendingMethod)));
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
                lines.add(CdxFormat.CDXJ.format(pending, filename, pendingOffset, pendingLength, urlKey(pending, pendingMethod)));
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
}
