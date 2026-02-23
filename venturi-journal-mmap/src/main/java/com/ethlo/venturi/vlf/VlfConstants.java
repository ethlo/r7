package com.ethlo.venturi.vlf;

public final class VlfConstants
{
    // --- File Header / Preamble ---
    /**
     * Magic bytes 'VLF1' for file identification
     */
    public static final int MAGIC = 0x564C4631;

    /**
     * The size of the self-describing preamble (4KB)
     */
    public static final int PREAMBLE_SIZE = 4096;

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

    // --- String Encoding Markers ---
    /**
     * 0xFF: Indicates the following byte is a Dictionary ID
     */
    public static final byte DICT_LOOKUP = (byte) 0xFF;

    static final int LONG_STRING_LENGTH_BOUNDARY = 0xFE;

    /**
     * 0xFE: Indicates a long string (next 4 bytes are length)
     */
    public static final byte LONG_STRING = (byte) 0xFE;

    /**
     * 0x00: Indicates a NULL or empty value
     */
    public static final byte NULL_VALUE = (byte) 0x00;

    private VlfConstants()
    {
    } // Prevent instantiation
}