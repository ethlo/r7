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
     * A consumer refused an entry, so the reader stopped at it rather than passing it by.
     * <p>
     * This is not damage. The entry is intact, it is still in the segment, and the reader
     * has left its position on it — the next pass will offer it again. Nothing after it in
     * that segment is read until it is accepted, which is deliberate: an audit log that
     * drops a record because a sink was briefly unavailable is not an audit log.
     * <p>
     * It is reported here rather than through {@link #onCorruptRegion} because the two ask
     * different things of an operator. A corrupt region is a fact about the file and there
     * is nothing to do about it; a stall is a fact about the consumer, and it clears by
     * itself the moment the consumer recovers.
     *
     * @param offset   byte offset of the entry that was refused
     * @param sequence its sequence number within the segment
     * @param cause    what the consumer threw
     */
    default void onDeliveryStalled(String segment, long offset, int sequence, Throwable cause)
    {
    }

    /**
     * Recovery sealed a segment at its last intact entry, after an unclean stop.
     * <p>
     * Nothing was cut from the file: the segment keeps its pre-allocated tail, and
     * {@code dataEnd} is what a reader bounds itself by. This event says the writer did not
     * get to finish, not that anything was removed.
     *
     * @param dataEnd        offset one past the last intact entry, recorded in the seal record
     * @param discardedBytes content past {@code dataEnd} that could not be read — zero for
     *                       the ordinary case, where everything past it is the untouched
     *                       remainder of the pre-allocation
     */
    default void onSegmentRecovered(String segment, long dataEnd, long discardedBytes, long recordsRecovered)
    {
    }
}
