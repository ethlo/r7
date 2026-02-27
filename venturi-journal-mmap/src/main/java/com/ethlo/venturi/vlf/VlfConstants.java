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

    public static final String VLF_FILE_EXTENSION = ".vlf";
    public static final String ACTIVE_FILE_EXTENSION = ".flux";
    public static final String COMPRESSED_FILE_EXTENSION = ".zst";

    private VlfConstants()
    {
    } // Prevent instantiation
}