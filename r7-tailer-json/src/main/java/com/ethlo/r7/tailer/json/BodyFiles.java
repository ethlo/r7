package com.ethlo.r7.tailer.json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.tailer.files.SealedFileWriter;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdInputStream;

/**
 * Captured bodies stored for the JSON output ({@code json.bodies: files}): each body is one
 * independent Zstandard frame (content size and checksum included), frames are concatenated
 * into rotating {@code .bodies.zst} files ({@link SealedFileWriter}), and the JSON line points
 * at a body with its file, offset and compressed length. Reading {@code length} bytes at
 * {@code offset} and decompressing them gives the body back; {@code zstd -d} on a whole file
 * gives every body in it, back to back. The file holds nothing else: the line is the index.
 * <p>
 * <b>One exchange, one unit.</b> {@link #write} appends the bodies of one exchange in one
 * append, before its line is written. If the line then fails, {@link #discard} takes the bodies
 * back, so the retry does not store them twice; once the line is written, {@link #commit} lets
 * later exchanges reuse them.
 * <p>
 * <b>Deduplication.</b> A body whose SHA-256 matches one already stored in the current file is
 * not written again: its pointer is the earlier body's. The digests are forgotten whenever a
 * new file is opened, so a pointer never leaves the file written in the same period, and a body
 * is remembered only after its line was written, so a pointer never targets bytes that were
 * taken back. SHA-256 rather than the journal's CRC32C, since bodies are chosen by callers and a
 * collision would point one caller's line at another caller's body.
 */
public final class BodyFiles implements AutoCloseable
{
    private static final Logger logger = LoggerFactory.getLogger(BodyFiles.class);

    static final String EXTENSION = ".bodies.zst";

    public static final int MIN_ZSTD_LEVEL = 1;
    public static final int MAX_ZSTD_LEVEL = Zstd.maxCompressionLevel();

    /**
     * Where a body is: the sealed file's name, the offset of its Zstandard frame, and the frame's
     * length.
     */
    public record BodyRef(String file, long offset, long length)
    {
    }

    /**
     * The bodies of one exchange, as {@link #write} stored them: a pointer for each body there
     * was, and what was appended to do so. Pass it to {@link #commit} once the line is written,
     * or to {@link #discard} if it failed.
     */
    public static final class Written
    {
        private final BodyRef request;
        private final BodyRef response;
        private final String file;
        private final long offset;
        private final long length;
        private final Map<String, BodyRef> stored;

        private Written(final BodyRef request, final BodyRef response, final String file, final long offset, final long length,
                        final Map<String, BodyRef> stored)
        {
            this.request = request;
            this.response = response;
            this.file = file;
            this.offset = offset;
            this.length = length;
            this.stored = stored;
        }

        /**
         * The request body's pointer, or {@code null} when there was no request body.
         */
        public BodyRef request()
        {
            return request;
        }

        /**
         * The response body's pointer, or {@code null} when there was no response body.
         */
        public BodyRef response()
        {
            return response;
        }
    }

    private final SealedFileWriter files;
    private final ZstdCompressCtx compressor;
    private final Map<String, BodyRef> digests;
    private String digestsFile;
    private Written lastWrite;

    /**
     * @param dedupCacheEntries how many body digests of the current file are remembered
     */
    public BodyFiles(final Path directory, final String filePrefix, final long maxFileSizeBytes, final long maxFileAgeMillis, final int zstdLevel,
                     final int dedupCacheEntries) throws IOException
    {
        if (zstdLevel < MIN_ZSTD_LEVEL || zstdLevel > MAX_ZSTD_LEVEL)
        {
            throw new IllegalArgumentException("zstdLevel must be between " + MIN_ZSTD_LEVEL + " and " + MAX_ZSTD_LEVEL + ", but was " + zstdLevel);
        }
        if (dedupCacheEntries <= 0)
        {
            throw new IllegalArgumentException("dedupCacheEntries must be positive, but was " + dedupCacheEntries);
        }
        this.digests = new LinkedHashMap<>(16, 0.75f, true)
        {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, BodyRef> eldest)
            {
                return size() > dedupCacheEntries;
            }
        };
        this.compressor = new ZstdCompressCtx();
        compressor.setLevel(zstdLevel);
        compressor.setChecksum(true);
        compressor.setContentSize(true);
        this.files = new SealedFileWriter(directory, filePrefix, maxFileSizeBytes, maxFileAgeMillis, new BodyFormat());
    }

    /**
     * Stores the bodies of one exchange in the current file, in one append, rolling first if the
     * file is full or stale. A body already in the file, by digest, is not written again; neither
     * is a response that repeats the request. If the append fails, the file is cut back to where
     * it started.
     *
     * @param request  the request body's fragments, or {@code null}
     * @param response the response body's fragments, or {@code null}
     */
    public synchronized Written write(final List<ByteBuffer> request, final List<ByteBuffer> response) throws IOException
    {
        lastWrite = null;
        // Roll before the append, never after it: a failure after the bodies are written would
        // have the tailer offer the exchange again. Age is checked here too, not only between
        // reads: one read of a backlog can outlast max_file_age.
        files.rollIfFull();
        files.rollIfStale();
        files.ensureOpen();
        final String file = files.fileName();
        if (!file.equals(digestsFile))
        {
            digests.clear();
            digestsFile = file;
        }

        final ByteArrayOutputStream frames = new ByteArrayOutputStream();
        final Map<String, BodyRef> stored = new LinkedHashMap<>(2);
        final BodyRef requestRef = store(request, file, frames, stored);
        final BodyRef responseRef = store(response, file, frames, stored);
        if (frames.size() == 0)
        {
            return new Written(requestRef, responseRef, file, files.size(), 0, Map.of());
        }

        // The pointers were built relative to the start of this append; the append returns where
        // that is (a cut-back retried first can move it).
        final long offset = files.append(frames.toByteArray());
        final Map<String, BodyRef> placed = new LinkedHashMap<>(stored.size());
        stored.forEach((digest, ref) -> placed.put(digest, place(ref, offset)));
        final Written written = new Written(place(requestRef, offset, stored), place(responseRef, offset, stored), file, offset, frames.size(), placed);
        lastWrite = written;
        return written;
    }

    /**
     * Remembers the bodies {@code written} stored, once the line pointing at them is written, so
     * later exchanges with the same bodies point at them too.
     */
    public synchronized void commit(final Written written)
    {
        if (written == lastWrite)
        {
            lastWrite = null;
        }
        if (written.file.equals(digestsFile))
        {
            written.stored.forEach(digests::putIfAbsent);
        }
    }

    /**
     * Takes back what {@code written} appended, when the line pointing at it failed: the exchange
     * is offered again, and without this its bodies would be stored twice. If the file cannot be
     * cut back, this throws, and the cut-back is retried before anything else is written to the
     * file or it is sealed.
     *
     * @param written what the last {@link #write} returned; anything else is a programming error
     */
    public synchronized void discard(final Written written) throws IOException
    {
        if (written.length == 0)
        {
            return;
        }
        if (written != lastWrite || !written.file.equals(files.fileName()) || files.size() != written.offset + written.length)
        {
            throw new IllegalStateException("Only the last write can be discarded, and only while its file is current: "
                    + written.file + " at " + written.offset);
        }
        lastWrite = null;
        files.discardFrom(written.offset);
    }

    /**
     * Seals the current file if it has reached the size limit, or is older than the age limit
     * and holds a body. Called after every read, so a quiet stream still produces finished files
     * on time.
     */
    public synchronized void rollIfStale() throws IOException
    {
        files.rollIfFull();
        files.rollIfStale();
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

    /**
     * Appends one body's frame to {@code frames}, unless the current file or this exchange
     * already holds the same bytes; the returned pointer's offset is relative to the start of
     * {@code frames}, except for a body already in the file.
     */
    private BodyRef store(final List<ByteBuffer> fragments, final String file, final ByteArrayOutputStream frames,
                          final Map<String, BodyRef> stored)
    {
        final byte[] body = bytes(fragments);
        if (body == null)
        {
            return null;
        }
        final String digest = sha256(body);
        final BodyRef earlier = digests.get(digest);
        if (earlier != null)
        {
            return earlier;
        }
        final BodyRef sameExchange = stored.get(digest);
        if (sameExchange != null)
        {
            return sameExchange;
        }
        final byte[] frame = compressor.compress(body);
        final BodyRef ref = new BodyRef(file, frames.size(), frame.length);
        frames.writeBytes(frame);
        stored.put(digest, ref);
        return ref;
    }

    /**
     * {@code ref} moved from relative to the append to absolute, when it is one this append
     * wrote; a pointer into the file from an earlier exchange is returned as is.
     */
    private static BodyRef place(final BodyRef ref, final long offset, final Map<String, BodyRef> stored)
    {
        // By identity: an earlier body's pointer can equal a relative one in value.
        for (final BodyRef written : stored.values())
        {
            if (written == ref)
            {
                return place(ref, offset);
            }
        }
        return ref;
    }

    private static BodyRef place(final BodyRef ref, final long offset)
    {
        return new BodyRef(ref.file(), offset + ref.offset(), ref.length());
    }

    /**
     * The body's bytes, or {@code null} when there is none.
     */
    private static byte[] bytes(final List<ByteBuffer> fragments)
    {
        if (fragments == null)
        {
            return null;
        }
        long total = 0;
        for (final ByteBuffer fragment : fragments)
        {
            total += fragment.remaining();
        }
        if (total == 0)
        {
            return null;
        }
        if (total > Integer.MAX_VALUE - 8)
        {
            throw new IllegalArgumentException("A body of " + total + " bytes is too large for one frame");
        }
        final byte[] body = new byte[(int) total];
        int position = 0;
        for (final ByteBuffer fragment : fragments)
        {
            final int length = fragment.remaining();
            fragment.duplicate().get(body, position, length);
            position += length;
        }
        return body;
    }

    private static String sha256(final byte[] body)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        }
        catch (final NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    /**
     * The end of the last complete, readable frame: a torn last frame (a crash mid-write) is cut,
     * and so is everything from a frame that does not decode, since nothing after it can be
     * trusted. Each frame is decompressed in full, as only its content checksum reveals damage
     * past its header.
     */
    static long endOfLastCompleteFrame(final FileChannel file) throws IOException
    {
        final long size = file.size();
        long end = 0;
        while (end < size)
        {
            final int windowSize = (int) Math.min(size - end, Integer.MAX_VALUE);
            final ByteBuffer window = file.map(FileChannel.MapMode.READ_ONLY, end, windowSize);
            int within = 0;
            while (within < windowSize)
            {
                final long frame = Zstd.findFrameCompressedSize(window.slice(within, windowSize - within));
                if (Zstd.isError(frame) || frame <= 0)
                {
                    break;
                }
                if (!decodes(window.slice(within, (int) frame)))
                {
                    logger.warn("Undecodable body at offset {}; recovering up to the body before it", end + within);
                    return end + within;
                }
                within += (int) frame;
            }
            final boolean lastWindow = within == 0 || end + windowSize == size;
            end += within;
            if (lastWindow)
            {
                break;
            }
        }
        return end;
    }

    private static boolean decodes(final ByteBuffer frame)
    {
        final byte[] compressed = new byte[frame.remaining()];
        frame.get(compressed);
        try (InputStream in = new ZstdInputStream(new ByteArrayInputStream(compressed)))
        {
            final byte[] sink = new byte[8192];
            while (in.read(sink) != -1)
            {
                // discard: reading to the end checks the content checksum
            }
            return true;
        }
        catch (final IOException | RuntimeException e)
        {
            return false;
        }
    }

    private static final class BodyFormat implements SealedFileWriter.Format
    {
        @Override
        public String extension()
        {
            return EXTENSION;
        }

        @Override
        public long endOfLastCompleteRecord(final FileChannel file) throws IOException
        {
            return endOfLastCompleteFrame(file);
        }
    }
}
