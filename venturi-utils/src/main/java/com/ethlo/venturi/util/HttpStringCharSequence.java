package com.ethlo.venturi.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A thin flyweight to fulfill the CharSequence contract
 * without creating a new java.lang.String object.
 */
public class HttpStringCharSequence implements CharSequence
{
    private final Object source;
    private final int hash;
    private final byte[] bytes;

    public HttpStringCharSequence(final Object source, final int hash, final byte[] bytes)
    {
        this.source = source;
        this.hash = hash;
        this.bytes = bytes;
    }

    @Override
    public int length()
    {
        return bytes.length;
    }

    @Override
    public char charAt(final int index)
    {
        return (char) (bytes[index] & 0xFF);
    }

    @Override
    public CharSequence subSequence(final int start, final int end)
    {
        return this.toString().substring(start, end);
    }

    @Override
    public String toString()
    {
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    public byte[] getBytes()
    {
        return bytes;
    }

    @Override
    public boolean equals(final Object o)
    {
        if (o == this)
        {
            return true;
        }

        if (o instanceof final HttpStringCharSequence that)
        {
            return that.bytes.length == this.bytes.length && Arrays.equals(this.bytes, that.bytes);
        }

        return false;
    }

    @Override
    public int hashCode()
    {
        return hash;
    }

    public Object getSource()
    {
        return source;
    }
}