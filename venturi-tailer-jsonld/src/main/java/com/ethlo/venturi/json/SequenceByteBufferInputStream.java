package com.ethlo.venturi.json;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;

public class SequenceByteBufferInputStream extends InputStream
{
    private final Iterator<ByteBuffer> iterator;
    private ByteBuffer current;

    public SequenceByteBufferInputStream(List<ByteBuffer> buffers)
    {
        this.iterator = buffers.iterator();
        advance();
    }

    private void advance()
    {
        if (iterator.hasNext())
        {
            // Duplicate to avoid mutating the original buffer's position
            current = iterator.next().duplicate();
        }
        else
        {
            current = null;
        }
    }

    @Override
    public int read()
    {
        while (current != null && !current.hasRemaining())
        {
            advance();
        }
        if (current == null) return -1;
        return current.get() & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len)
    {
        while (current != null && !current.hasRemaining())
        {
            advance();
        }

        if (current == null)
        {
            return -1;
        }
        int toRead = Math.min(len, current.remaining());
        current.get(b, off, toRead);
        return toRead;
    }
}