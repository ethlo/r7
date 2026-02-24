package com.ethlo.venturi.vlf;

public final class VlfConstants
{
    // --- File Header / Preamble ---
    /**
     * Magic bytes 'VLF1' for file identification
     */
    public static final int MAGIC = 0x564C4631;

    /**
     * The size of the self-describing preamble (1KB)
     */
    public static final int PREAMBLE_SIZE = 1024;

    /**
     * Current version of the binary protocol
     */
    public static final short VERSION_1 = 1;

    // --- Control Markers ---
    /**
     * Indicates the start of an exchange (BEGIN)
     */
    public static final byte MARKER_START = (byte) 0x01;

    /**
     * Indicates a body chunk
     */
    public static final byte MARKER_BODY = (byte) 0x02;

    /**
     * Indicates the completion of an exchange (END)
     */
    public static final byte MARKER_END = (byte) 0x03;

    private VlfConstants()
    {
    } // Prevent instantiation
}