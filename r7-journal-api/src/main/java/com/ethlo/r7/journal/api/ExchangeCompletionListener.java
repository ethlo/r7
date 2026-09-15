package com.ethlo.r7.journal.api;

/**
 * Sink for exchanges rebuilt from the journal.
 * <p>
 * Beyond the happy path a reader can tell several distinct things went wrong, and they
 * mean different things to a consumer: an exchange that ended without enough context is
 * still a loggable record, an exchange that never ended is truncated evidence, a body
 * checksum mismatch is an integrity problem, and an end event with nothing in flight is
 * usually just a reader that started mid-stream.
 * <p>
 * Failures are reported at two granularities. {@link #onIncomplete} sees every exchange
 * that will not be completed; {@link #onIncompleteEnd} and {@link #onAbandoned} split
 * those by whether an EndExchange event was ever seen, which decides what a consumer can
 * actually write. Overriding the specific methods replaces the coarse one for that case,
 * so implement whichever granularity the consumer needs — not both.
 * <p>
 * Every method except {@link #onComplete} has a default, so a consumer that only cares
 * about finished exchanges implements one method.
 */
public interface ExchangeCompletionListener
{
    /**
     * A fully reconstructed request/response pair.
     */
    void onComplete(JournalExchange exchange);

    /**
     * An exchange that will never be completed and is being dropped.
     * <p>
     * Called by the default implementations of {@link #onIncompleteEnd} and
     * {@link #onAbandoned}, for consumers that treat all incomplete exchanges alike.
     * Note that the state of {@code exchange} depends on the reason — see
     * {@link IncompleteReason#hasEndEvent()}.
     */
    default void onIncomplete(JournalExchange exchange, IncompleteReason reason)
    {
    }

    /**
     * An EndExchange arrived, but the exchange is not usable as a complete record.
     * <p>
     * Status, timing, traffic counters, attributes and the journaled body checksums have
     * all been applied, so this is terminal: everything the journal holds about this
     * request is present. A logger can emit it as a partial record rather than discard it.
     *
     * @param reason always one where {@link IncompleteReason#hasEndEvent()} is true
     */
    default void onIncompleteEnd(JournalExchange exchange, IncompleteReason reason)
    {
        onIncomplete(exchange, reason);
    }

    /**
     * No EndExchange ever arrived, and the exchange has been dropped.
     * <p>
     * Unlike {@link #onIncompleteEnd}, nothing from the end event has been applied: there
     * is no status, no end timestamp and no traffic counters, and any body is truncated
     * at whatever was journaled before the stream stopped. A logger that emits these must
     * mark them as such — the absent fields are unknown, not zero.
     *
     * @param reason always one where {@link IncompleteReason#hasEndEvent()} is false
     */
    default void onAbandoned(JournalExchange exchange, IncompleteReason reason)
    {
        onIncomplete(exchange, reason);
    }

    /**
     * An EndExchange arrived for a request id with nothing in flight.
     * <p>
     * Expected when a reader starts mid-stream or the start event lived in a segment this
     * reader has not seen; suspicious in steady state. There is no exchange to hand over,
     * which is why this is not an {@link #onIncompleteEnd}.
     */
    default void onOrphanedEnd(String requestId)
    {
    }

    /**
     * A body chunk arrived for a request id with no preceding start event.
     */
    default void onOrphanedBody(String requestId, BodyKind kind)
    {
    }

    /**
     * The body bytes stored in the journal do not checksum to the value the gateway
     * recorded at the time of the exchange — the stored record is not what crossed the
     * wire.
     *
     * @param journaled the CRC32C the gateway wrote into the EndExchange event
     * @param observed  the CRC32C of the body fragments actually read back
     */
    default void onChecksumMismatch(JournalExchange exchange, BodyKind kind, int journaled, int observed)
    {
    }

    enum IncompleteReason
    {
        /**
         * An end event was seen, but no client request start line was ever recorded.
         */
        NO_START_EVENT(true),

        /**
         * An end event was seen, but carried no usable status.
         */
        NO_STATUS(true),

        /**
         * No end event arrived within the reassembler's maximum age.
         */
        TIMED_OUT(false),

        /**
         * Dropped to keep the in-flight set within its ceiling, before any end arrived.
         */
        CAPACITY_EVICTED(false);

        private final boolean hasEndEvent;

        IncompleteReason(final boolean hasEndEvent)
        {
            this.hasEndEvent = hasEndEvent;
        }

        /**
         * Whether the EndExchange event was seen, and therefore whether status, timing
         * and traffic counters are populated on the exchange.
         */
        public boolean hasEndEvent()
        {
            return hasEndEvent;
        }
    }

    enum BodyKind
    {
        REQUEST,
        RESPONSE
    }
}
