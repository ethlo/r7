package com.ethlo.venturi.core.storage.mmap;

public final class Marker
{
    public static final byte VERSION = 0x01;
    public static final byte BEGIN = 0x01;
    public static final byte BODY = 0x02;
    public static final byte END = 0x03;

    private Marker()
    {
    } // Prevent instantiation
}