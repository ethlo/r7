package com.ethlo.r7.r7f;

import com.ethlo.r7.journal.api.R7fFileNaming;

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
     * The first format version: self-delimiting entries, resynchronised by scanning for the
     * entry magic. Not readable by this build; named so that the refusal can say what it found.
     */
    public static final short VERSION_1 = 1;

    /**
     * Block framing. See FORMAT.md.
     */
    public static final short VERSION_2 = 2;

    /**
     * The version written by this implementation, and the only one it reads. FORMAT.md 11:
     * there is no compatibility path between versions.
     */
    public static final short CURRENT_VERSION = VERSION_2;

    // --- Blocks ---
    /**
     * The block size this implementation writes. Recorded in every segment's preamble, so a
     * reader never assumes it; a constant rather than a setting until there is a reason to
     * tune it.
     * <p>
     * It bounds what damage costs: after a hole or a bad checksum the reader resumes at the
     * next block boundary, so up to one block of intact entries is lost per damaged region. It
     * also sets how many entries are split across blocks, which costs the reader a copy.
     */
    public static final int DEFAULT_BLOCK_SIZE = 32 * 1024;

    public static final int MIN_BLOCK_SIZE = 4 * 1024;
    public static final int MAX_BLOCK_SIZE = 1024 * 1024;

    /**
     * The one codec this version defines: fragment data stored as written.
     */
    public static final short CODEC_NONE = 0;

    /**
     * A zstd stream per block (FORMAT.md 4.4). Every entry is fed into it and flushed, and its
     * compressed bytes are that entry's FULL fragment, so each entry is still committed by its
     * own magic. A fragment's Flags say how its data relates to the stream. Entries too large
     * for a block are compressed on their own and split as usual.
     */
    public static final short CODEC_ZSTD = 1;

    /** First entry of a block's stream: the decompressor starts fresh here. */
    public static final byte FLAG_STREAM_START = 1;
    /** Continues the block's stream: needs every earlier stream fragment in the block. */
    public static final byte FLAG_STREAM_CONTINUE = 2;
    /** Not an entry: the rest of the block, skipped because the next entry might not fit. */
    public static final byte FLAG_PAD = 4;
    /** {@code plainLen(4)} then a zstd frame of its own; split across blocks like any entry. */
    public static final byte FLAG_STANDALONE = 8;

    public static boolean isKnownCodec(final short codec)
    {
        return codec == CODEC_NONE || codec == CODEC_ZSTD;
    }

    // --- Fragment framing ---
    /**
     * Magic of every fragment: 'R7F2'. For a FULL or FIRST fragment it is the entry's commit,
     * written last.
     */
    public static final int FRAGMENT_MAGIC = 0x52374632;

    /**
     * magic(4) type(1) flags(1) reserved(2) length(4) crc32c(4)
     */
    public static final int FRAGMENT_HEADER_SIZE = 16;

    /**
     * A header and one data byte. A block with less than this left is padding, by position.
     */
    public static final int MIN_FRAGMENT_SIZE = FRAGMENT_HEADER_SIZE + 1;

    public static final int FRAGMENT_OFF_TYPE = 4;
    public static final int FRAGMENT_OFF_FLAGS = 5;
    public static final int FRAGMENT_OFF_RESERVED = 6;
    public static final int FRAGMENT_OFF_LENGTH = 8;
    public static final int FRAGMENT_OFF_CRC = 12;

    public static final byte FRAGMENT_FULL = 1;
    public static final byte FRAGMENT_FIRST = 2;
    public static final byte FRAGMENT_MIDDLE = 3;
    public static final byte FRAGMENT_LAST = 4;

    /**
     * The start of an entry's content: sequence(4) fbLen(4) rawLen(4). The payloads follow.
     */
    public static final int ENTRY_CONTENT_HEADER_SIZE = 3 * Integer.BYTES;

    /**
     * Sequence number of the first entry in every segment.
     */
    public static final int FIRST_ENTRY_SEQUENCE = 1;

    /**
     * Value stored in an EndExchange event's body checksum field when the writer computed no
     * checksum for that direction.
     * <p>
     * A checksum cannot signal its own absence — every 32-bit value, zero included, is the
     * legitimate CRC32C of some input — so the sentinel has to live outside the value domain.
     * That is why the field is a {@code long} holding the unsigned 32-bit value: as an
     * {@code int}, {@code -1} is the perfectly ordinary checksum {@code 0xFFFFFFFF}, and one
     * body in four billion would have had its verification skipped by the very mechanism
     * meant to guarantee it.
     * <p>
     * This lives here, with the format's other constants, and not on {@code BodyChecksum}.
     * An encoding is a fact about a file, and a type that exposes one hands every consumer a
     * never-throwing accessor that answers a number for "no checksum" — which is how a
     * {@code -1} ends up in a JSON column. Only this module encodes and decodes it, and it
     * must equal the default declared for those fields in {@code journal.fbs}, so that a
     * writer which omits them and a writer which stores the sentinel read back the same.
     */
    public static final long CHECKSUM_ABSENT = -1L;

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
     * Offset one past the last fragment of the segment's last entry. This is what a reader bounds itself by, so
     * that the zero-filled remainder of the pre-allocation is not data and not a hole — it
     * is simply outside the file's contents.
     * <p>
     * It is what lets sealing stop truncating altogether. Where the data ends used to be
     * inferred from the file's size, which meant every seal had to cut the tail for the
     * inference to hold — and cutting a file another process may have mapped is a SIGBUS.
     * Recorded rather than inferred, nothing has to shrink anything.
     */
    public static final int PREAMBLE_OFF_DATA_END = 38;

    /**
     * Seal flags. Zero for a segment sealed by a healthy writer.
     */
    public static final int PREAMBLE_OFF_SEAL_FLAGS = 46;

    /**
     * Not part of the seal record: written with the rest of the preamble when the segment is
     * claimed, and never changed.
     */
    public static final int PREAMBLE_OFF_BLOCK_SIZE = 50;
    public static final int PREAMBLE_OFF_CODEC = 54;

    /**
     * Content past Data End that a reader declined to deliver, rather than content that was
     * lost. Recovery sets this when its scan stopped on a sequence regression: the entries
     * after that point are structurally readable, they are simply not safe to replay.
     * <p>
     * A reader that finishes such a segment must not delete it. This is the same rule the
     * tailer applies when <em>it</em> stops on a regression — damage is deleted, undelivered
     * content is kept — carried in the file so that the decision survives recovery handing
     * the segment over to a different process.
     */
    public static final int SEAL_FLAG_RETAIN = 0x1;

    // --- File extensions ---
    // Re-exposed from r7-journal-api's R7fFileNaming, the single source of truth shared with
    // tools (like the reaper) that recognize a segment's lifecycle stage without depending on
    // this engine module.
    public static final String R7F_FILE_EXTENSION = R7fFileNaming.SEALED_FILE_EXTENSION;
    public static final String ACTIVE_FILE_EXTENSION = R7fFileNaming.ACTIVE_FILE_EXTENSION;
    /**
     * Not part of a segment's life. A segment goes {@code .flux} → {@code .r7f} and stops
     * there; compression is one thing a consumer may choose to do with a sealed segment,
     * and its output belongs to that consumer rather than to this directory. Kept as the
     * conventional suffix for consumer-produced compressed output.
     */
    public static final String COMPRESSED_FILE_EXTENSION = ".zst";
    public static final String CORRUPT_FILE_EXTENSION = R7fFileNaming.CORRUPT_FILE_EXTENSION;

    private R7fConstants()
    {
    } // Prevent instantiation
}
