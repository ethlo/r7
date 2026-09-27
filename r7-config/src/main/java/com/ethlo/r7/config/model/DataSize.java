package com.ethlo.r7.config.model;

import java.util.StringJoiner;

public record DataSize(long bytes)
{
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

    @Override
    public String toString()
    {
        return new StringJoiner(", ", DataSize.class.getSimpleName() + "[", "]")
                .add("bytes=" + bytes)
                .toString();
    }
}
