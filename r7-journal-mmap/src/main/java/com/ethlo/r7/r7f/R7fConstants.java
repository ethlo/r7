package com.ethlo.r7.r7f;

public final class R7fConstants
{
    // --- File Header / Preamble ---
    /**
     * Magic bytes 'R7F1' for file identification
     */
    public static final int MAGIC = 0x52374631;
    /**
     * The size of the self-describing preamble (1KB)
     */
    public static final int PREAMBLE_SIZE = 1024;

    /**
     * Current version of the binary protocol
     */
    public static final short VERSION_1 = 1;

    public static final String R7F_FILE_EXTENSION = ".r7f";
    public static final String ACTIVE_FILE_EXTENSION = ".flux";
    public static final String COMPRESSED_FILE_EXTENSION = ".zst";

    private R7fConstants()
    {
    } // Prevent instantiation
}