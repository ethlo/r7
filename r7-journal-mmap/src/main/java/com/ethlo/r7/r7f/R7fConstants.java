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

    /**
     * Magic stamped into the preamble when a segment is sealed: 'R7FS'.
     * <p>
     * Until this is present the seal record below is meaningless. It is written last, after
     * the count and the last sequence, for the same reason an entry's magic is written last:
     * it is the commit, and a reader must never see it without the facts behind it.
     * <p>
     * It also makes "sealed" a property of the bytes rather than only of the file name. A
     * {@code .r7f} without it was renamed without being sealed, which is a fault the reader
     * can see rather than infer.
     */
    public static final int SEAL_MAGIC = 0x52374653;

    // --- Preamble field offsets ---
    public static final int PREAMBLE_OFF_MAGIC = 0;
    public static final int PREAMBLE_OFF_VERSION = 4;
    public static final int PREAMBLE_OFF_SEGMENT_SEQUENCE = 6;
    public static final int PREAMBLE_OFF_CREATED_EPOCH_MILLIS = 14;

    // --- Seal record: zero until the segment is sealed ---
    public static final int PREAMBLE_OFF_SEAL_MAGIC = 22;
    public static final int PREAMBLE_OFF_ENTRY_COUNT = 26;
    public static final int PREAMBLE_OFF_LAST_SEQUENCE = 34;

    /**
     * Offset one past the segment's last entry. This is what a reader bounds itself by, so
     * that the zero-filled remainder of the pre-allocation is not data and not a hole — it
     * is simply outside the file's contents.
     * <p>
     * It is what lets sealing stop truncating altogether. Where the data ends used to be
     * inferred from the file's size, which meant every seal had to cut the tail for the
     * inference to hold — and cutting a file another process may have mapped is a SIGBUS.
     * Recorded rather than inferred, nothing has to shrink anything.
     */
    public static final int PREAMBLE_OFF_DATA_END = 38;

    // --- File extensions ---
    public static final String R7F_FILE_EXTENSION = ".r7f";
    public static final String ACTIVE_FILE_EXTENSION = ".flux";
    /**
     * Not part of a segment's life. A segment goes {@code .flux} → {@code .r7f} and stops
     * there; compression is one thing a consumer may choose to do with a sealed segment,
     * and its output belongs to that consumer rather than to this directory. Kept because
     * {@code R7fCompressionEngine} is still available for a consumer that wants it.
     */
    public static final String COMPRESSED_FILE_EXTENSION = ".zst";
    public static final String CORRUPT_FILE_EXTENSION = ".corrupt";

    private R7fConstants()
    {
    } // Prevent instantiation
}
