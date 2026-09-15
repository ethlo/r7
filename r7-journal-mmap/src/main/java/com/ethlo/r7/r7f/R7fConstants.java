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
     * The one format version. See FORMAT.md.
     */
    public static final short VERSION_1 = 1;

    /**
     * The version written by this implementation.
     */
    public static final short CURRENT_VERSION = VERSION_1;

    // --- Entry framing ---
    /**
     * magic + sequence + payloadLen + fbLen + rawLen
     */
    public static final int ENTRY_HEADER_SIZE = 5 * Integer.BYTES;

    /**
     * Entry header plus the trailing CRC32C; the size of an entry carrying no payload.
     */
    public static final int MIN_ENTRY_SIZE = ENTRY_HEADER_SIZE + Integer.BYTES;

    /**
     * Sequence number of the first entry in every segment.
     */
    public static final int FIRST_ENTRY_SEQUENCE = 1;

    // --- Preamble field offsets ---
    public static final int PREAMBLE_OFF_MAGIC = 0;
    public static final int PREAMBLE_OFF_VERSION = 4;
    public static final int PREAMBLE_OFF_SEGMENT_SEQUENCE = 6;
    public static final int PREAMBLE_OFF_CREATED_EPOCH_MILLIS = 14;

    // --- File extensions ---
    public static final String R7F_FILE_EXTENSION = ".r7f";
    public static final String ACTIVE_FILE_EXTENSION = ".flux";
    public static final String COMPRESSED_FILE_EXTENSION = ".zst";
    public static final String CORRUPT_FILE_EXTENSION = ".corrupt";

    private R7fConstants()
    {
    } // Prevent instantiation
}
