package com.ethlo.r7.r7f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.luben.zstd.ZstdDecompressCtx;

/**
 * The working memory a {@link FragmentReader} needs beyond the segment itself: the zstd
 * decompression context for batches (FORMAT.md 4.4), the buffer a batch is inflated into, and
 * the buffer a split entry is reassembled in.
 * <p>
 * Kept apart from the reader because a reader is bound to one segment, while a tailer reads
 * the same shards over and over, every time the writer signals a commit. Creating these per
 * read was the tailer's native memory leak: zstd-jni frees a context only on
 * {@link ZstdDecompressCtx#close()}, with no cleaner or finalizer behind it, so every
 * {@link FragmentReader} that inflated a batch left its context behind for good. Whoever
 * reads repeatedly owns one of these and closes it when done; each buffer grows to the
 * largest need seen and is then reused.
 * <p>
 * Not thread-safe; one per reading thread. After {@link #close()} it may be used again, and
 * creates what it needs afresh.
 */
final class DecodeScratch implements AutoCloseable
{
    /** Contexts created and not yet closed, across all instances; for tests. */
    private static final AtomicInteger OPEN_CONTEXTS = new AtomicInteger();

    private ZstdDecompressCtx zstd;
    private ByteBuffer inflated;
    private ByteBuffer assembly;

    ZstdDecompressCtx zstd()
    {
        if (zstd == null)
        {
            zstd = new ZstdDecompressCtx();
            OPEN_CONTEXTS.incrementAndGet();
        }
        return zstd;
    }

    /**
     * A direct buffer of at least {@code capacity} bytes for an inflated batch, allocated at
     * {@code minimum} or more so that ordinary batches do not grow it one step at a time.
     */
    ByteBuffer inflated(final int capacity, final int minimum)
    {
        if (inflated == null || inflated.capacity() < capacity)
        {
            inflated = ByteBuffer.allocateDirect(Math.max(capacity, minimum)).order(ByteOrder.BIG_ENDIAN);
        }
        return inflated;
    }

    /**
     * A buffer of at least {@code capacity} bytes to reassemble a split entry in; direct when
     * asked for, since zstd-jni decompresses from direct buffers only.
     */
    ByteBuffer assembly(final int capacity, final int minimum, final boolean direct)
    {
        if (assembly == null || assembly.capacity() < capacity || assembly.isDirect() != direct)
        {
            final int size = Math.max(capacity, minimum);
            assembly = (direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size)).order(ByteOrder.BIG_ENDIAN);
        }
        return assembly;
    }

    /** Frees the zstd context now; the buffers go with the last reference to them. */
    @Override
    public void close()
    {
        if (zstd != null)
        {
            zstd.close();
            zstd = null;
            OPEN_CONTEXTS.decrementAndGet();
        }
        inflated = null;
        assembly = null;
    }

    static int openContexts()
    {
        return OPEN_CONTEXTS.get();
    }
}
