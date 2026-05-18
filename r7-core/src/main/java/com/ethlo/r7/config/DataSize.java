package com.ethlo.r7.config;

public class DataSize
{
    private final long bytes;

    public DataSize(final long bytes)
    {
        this.bytes = bytes;
    }

    public static DataSize ofMegabytes(long amount)
    {
        return ofKilobytes(amount * 1024L);
    }

    public static DataSize ofKilobytes(long amount)
    {
        return ofBytes(amount * 1024L);
    }

    public static DataSize ofBytes(long amount)
    {
        return new DataSize(amount);
    }

    public static DataSize ofGigabytes(long amount)
    {
        return ofMegabytes(amount * 1024L);
    }

    public long toBytes()
    {
        return bytes;
    }
}
