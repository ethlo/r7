package com.ethlo.r7.journal.api;

/**
 * Observer for damage to the journal itself, as opposed to problems with an individual
 * exchange.
 * <p>
 * These two kinds of failure belong to different audiences. An operator wants to know
 * that a segment lost 900 entries; the consumer of the log wants to know which requests
 * are therefore absent. Keeping the two listeners separate means a consumer that only
 * reads exchanges is not forced to care about file layout, and a monitoring integration
 * that only cares about integrity does not have to implement an exchange sink.
 * <p>
 * All methods default to no-ops. Segments are identified by file name rather than
 * {@code Path} so that this interface stays usable by consumers that never see the
 * filesystem.
 */
public interface JournalIntegrityListener
{
    JournalIntegrityListener NOOP = new JournalIntegrityListener()
    {
    };

    /**
     * Entry sequence numbers jumped forward: entries that were written are not present.
     * <p>
     * Append-only writing cannot produce this on its own — it means writeback did not
     * complete, typically after a power loss. The entries are unrecoverable; this call is
     * how their absence becomes visible instead of silent.
     *
     * @param segment          file the gap was found in
     * @param offset           byte offset of the entry that follows the gap
     * @param expectedSequence sequence the reader was expecting
     * @param foundSequence    sequence actually found
     * @param missingCount     number of entries proven absent
     */
    default void onEntriesMissing(String segment, long offset, int expectedSequence, int foundSequence, int missingCount)
    {
    }

    /**
     * A region failed structural validation or its CRC and was skipped.
     *
     * @param bytesSkipped bytes discarded before the reader resynchronised
     */
    default void onCorruptRegion(String segment, long offset, long bytesSkipped, String reason)
    {
    }

    /**
     * Entry sequence numbers went backwards, which a valid append-only segment cannot do.
     */
    default void onSequenceRegression(String segment, long offset, int expectedSequence, int foundSequence)
    {
    }

    /**
     * A segment could not be interpreted at all and was set aside rather than deleted.
     */
    default void onSegmentQuarantined(String segment, String reason)
    {
    }

    /**
     * Recovery sealed a segment by cutting it back to its last intact entry.
     *
     * @param validBytes     size the segment was truncated to
     * @param discardedBytes bytes beyond that point, including the pre-allocated tail
     */
    default void onSegmentTruncated(String segment, long validBytes, long discardedBytes, long recordsRecovered)
    {
    }
}
