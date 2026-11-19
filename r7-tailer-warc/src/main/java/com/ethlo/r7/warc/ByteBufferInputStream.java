package com.ethlo.r7.warc;

import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Reads a buffer's remaining bytes, advancing its position: how a mapped Zstandard frame is
 * handed to a decompressing stream.
 */
final class ByteBufferInputStream extends InputStream
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
