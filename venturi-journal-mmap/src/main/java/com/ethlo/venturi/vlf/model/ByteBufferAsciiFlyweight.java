package com.ethlo.venturi.vlf.model;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import com.ethlo.venturi.util.CharSequenceUtil;

/**
 * A zero-allocation US-ASCII string view over a shared ByteBuffer.
 * Instances of this class do not mutate the underlying buffer's position or limit.
 */
public final class ByteBufferAsciiFlyweight implements CharSequence
{
    private final ByteBuffer buffer;
    private final int offset;
    private final int length;

    public ByteBufferAsciiFlyweight(ByteBuffer buffer, int offset, int length)
    {
        this.buffer = buffer;
        this.offset = offset;
        this.length = length;
    }

    @Override
    public int length()
    {
        return length;
    }

    @Override
    public char charAt(int index)
    {
        if (index < 0 || index >= length)
        {
            throw new IndexOutOfBoundsException("Index: " + index + ", Length: " + length);
        }

        // Absolute get() prevents mutating the buffer's internal position state.
        return (char) (buffer.get(offset + index) & 0xFF);
    }

    @Override
    public CharSequence subSequence(int start, int end)
    {
        if (start < 0 || end > length || start > end)
        {
            throw new IndexOutOfBoundsException("start: " + start + ", end: " + end + ", length: " + length);
        }

        // Returns a new flyweight mapped to the subset of the bytes. 
        // This is a minimal allocation, but keep it off the hottest paths if possible.
        return new ByteBufferAsciiFlyweight(buffer, offset + start, end - start);
    }

    @Override
    public String toString()
    {
        if (length == 0)
        {
            return "";
        }

        // Materialize the string only when explicitly demanded.
        byte[] bytes = new byte[length];
        buffer.get(offset, bytes, 0, length);

        return new String(bytes, StandardCharsets.US_ASCII);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }

        if (!(obj instanceof CharSequence other))
        {
            return false;
        }

        return CharSequenceUtil.equals(this, other);
    }

    @Override
    public int hashCode()
    {
        return CharSequenceUtil.hash(this);
    }
}