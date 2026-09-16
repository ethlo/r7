package com.ethlo.r7.r7f;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.api.GatewayAttributes;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.ExchangeCompletionListener.BodyKind;
import com.ethlo.r7.journal.api.ExchangeCompletionListener.IncompleteReason;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.r7f.util.StringExchangeMap;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * Rebuilds whole exchanges from the interleaved event stream.
 * <p>
 * Exchanges are held in memory until their EndExchange event arrives. An exchange whose
 * end never arrives — because the segment holding it was lost, or the writer stopped
 * mid-exchange — would otherwise be held forever, so incomplete exchanges are aged out
 * and counted rather than accumulating.
 */
public class ExchangeReassembler implements JournalEventListener
{
    private static final Logger logger = LoggerFactory.getLogger(ExchangeReassembler.class);

    private final StringExchangeMap inFlight = new StringExchangeMap(10_000);
    private final ExchangeCompletionListener output;
    private final ReassemblyOptions options;
    private final long maxAgeNanos;

    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong incompleteEnds = new AtomicLong();
    private final AtomicLong abandoned = new AtomicLong();
    private final AtomicLong orphanedEnds = new AtomicLong();
    private final AtomicLong orphanedBodies = new AtomicLong();
    private final AtomicLong checksumMismatches = new AtomicLong();

    private int eventsSinceSweep;

    public ExchangeReassembler(ExchangeCompletionListener output)
    {
        this(output, ReassemblyOptions.DEFAULTS);
    }

    public ExchangeReassembler(ExchangeCompletionListener output, ReassemblyOptions options)
    {
        this.output = output;
        this.options = options;
        this.maxAgeNanos = options.maxAge().toNanos();
    }

    public ReassemblyOptions getOptions()
    {
        return options;
    }

    @Override
    public void onClientRequest(String reqId, JournalLevel level, String startLine, GatewayHeaders headers, InetAddress remoteAddress, IpSource ipSource)
    {
        getOrCreate(reqId).setClientRequest(startLine, level, copyOf(headers), remoteAddress, ipSource);
    }

    @Override
    public void onUpstreamRequest(String reqId, JournalLevel level, String startLine, GatewayHeaders headers)
    {
        getOrCreate(reqId).setUpstreamRequest(startLine, level, copyOf(headers));
    }

    @Override
    public void onUpstreamResponse(String reqId, JournalLevel level, String startLine, GatewayHeaders headers)
    {
        getOrCreate(reqId).setUpstreamResponse(startLine, level, copyOf(headers));
    }

    @Override
    public void onClientResponse(String reqId, JournalLevel level, String startLine, GatewayHeaders headers)
    {
        getOrCreate(reqId).setClientResponse(startLine, level, copyOf(headers));
    }

    @Override
    public void onRequestBody(String reqId, ByteBuffer bodyChunk)
    {
        final JournalExchange exchange = inFlight.get(reqId);
        if (validateExchangeExists(exchange, reqId, BodyKind.REQUEST))
        {
            // The checksum is accumulated on the exchange itself, so interleaved
            // exchanges cannot contaminate each other's value.
            exchange.appendRequestBody(bodyChunk);
        }
    }

    @Override
    public void onResponseBody(String reqId, ByteBuffer bodyChunk)
    {
        final JournalExchange exchange = inFlight.get(reqId);
        if (validateExchangeExists(exchange, reqId, BodyKind.RESPONSE))
        {
            exchange.appendResponseBody(bodyChunk);
        }
    }

    @Override
    public void onEnd(String reqId, GatewayAttributes attributes,
                      long clientStartTs, long clientEndTs,
                      int status,
                      long requestHeaderBytes, long requestBodyBytes, long responseHeaderBytes, long responseBodyBytes,
                      long proxyStartTs, long proxyFirstByteReceivedTs, long proxyEndTs,
                      final BodyChecksum requestChecksum, final BodyChecksum responseChecksum)
    {
        final JournalExchange exchange = inFlight.remove(reqId);

        if (exchange == null)
        {
            // Expected during shard boundaries or tailer startup
            orphanedEnds.incrementAndGet();
            output.onOrphanedEnd(reqId);
            if (logger.isDebugEnabled())
            {
                logger.debug("Received END for {} but no metadata exists. Skipping orphan.", reqId);
            }
            return;
        }

        // Apply final metrics and forensic checksums
        exchange.setTiming(clientStartTs, clientEndTs, proxyStartTs, proxyFirstByteReceivedTs, proxyEndTs);
        exchange.setTraffic(requestHeaderBytes, requestBodyBytes, responseHeaderBytes, responseBodyBytes);
        exchange.setAttributes(copyOf(attributes));
        exchange.setStatus(status);
        exchange.setJournalChecksums(requestChecksum, responseChecksum);

        try
        {
            // Inside the guard, not before it. Verification reports through
            // output.onChecksumMismatch, which is consumer code like any other — and a
            // consumer that throws from it was refusing the entry while this method had
            // already taken the exchange out of the map, so the decoder's rewind produced an
            // orphaned end on the retry and lost every fragment gathered before it. The rule
            // is not "guard the delivery call"; it is that nothing which can reach the
            // consumer may run outside the region that puts the exchange back.
            verifyChecksums(exchange, requestChecksum, responseChecksum);

            if (isExchangeComplete(exchange))
            {
                // Counted after the call, not before: a refusal below rewinds the exchange
                // for a later attempt, and a counter bumped on every attempt would say more
                // exchanges completed than a consumer ever received.
                output.onComplete(exchange);
                completed.incrementAndGet();
            }
            else
            {
                final IncompleteReason reason = exchange.getClientRequestStartLine() == null
                        ? IncompleteReason.NO_START_EVENT
                        : IncompleteReason.NO_STATUS;
                output.onIncompleteEnd(exchange, reason);
                incompleteEnds.incrementAndGet();
                logger.warn("Exchange {} ended but is not a complete record: {}", reqId, reason);
            }
        }
        catch (final RuntimeException e)
        {
            // The consumer refused this exchange, so the decoder will offer the end entry
            // again (FORMAT.md §6). Put the assembled exchange back first: everything before
            // the end event — the start line, the headers, every body fragment — lives only
            // here, and a retry against an empty map would report an orphaned end and hand
            // the consumer a record missing everything but its final metrics.
            //
            // Removing first and restoring on failure, rather than removing last, keeps the
            // success path — every exchange, always — a single map operation.
            inFlight.put(reqId, exchange);
            throw e;
        }

        maybeSweep();
    }

    /**
     * Compares what the gateway recorded against what was actually read back. A mismatch
     * means the body bytes in the journal are not the bytes that crossed the wire, which
     * is exactly what an audit trail exists to rule out.
     */
    private void verifyChecksums(final JournalExchange exchange, final BodyChecksum journaledRequest, final BodyChecksum journaledResponse)
    {
        // A recorded checksum is the whole condition. The writer only has one to record if
        // it passed body bytes to the journal, so the value's presence already carries
        // everything an extra guard could have told us — and it carries it in the one field
        // that cannot be lost independently of the checksum itself.
        //
        // This used to also require the start event's journal level and a positive body-byte
        // count. Those come from other entries: the level from the client request, the byte
        // count from the end event's traffic metrics. Requiring them turned a sufficient
        // condition into a conjunction that fails open — lose the start entry, or record the
        // wrong byte count, and verification is skipped precisely on the exchange whose
        // record is already damaged. The gate was load-bearing only while the writer had no
        // way to say "I did not compute one"; BodyChecksum says it now, as a type rather
        // than as a value every reader has to remember to exclude.
        //
        // An observed NOT_RECORDED means no body came back at all, and it is unequal to any
        // recorded checksum — so the "the writer hashed a body the reader never saw" case
        // falls out of the same comparison instead of needing a null check beside it.
        if (journaledRequest.isRecorded())
        {
            final BodyChecksum observed = exchange.getObservedRequestChecksum();
            if (!journaledRequest.equals(observed))
            {
                reportMismatch(exchange, BodyKind.REQUEST, journaledRequest, observed);
            }
        }

        if (journaledResponse.isRecorded())
        {
            final BodyChecksum observed = exchange.getObservedResponseChecksum();
            if (!journaledResponse.equals(observed))
            {
                reportMismatch(exchange, BodyKind.RESPONSE, journaledResponse, observed);
            }
        }
    }

    /**
     * Reports a mismatch once in full and then by count.
     * <p>
     * A mismatch is usually systemic rather than isolated — a writer that records the
     * wrong thing produces one per exchange — so logging every occurrence at ERROR buries
     * the rest of the log under hundreds of thousands of identical lines and tells an
     * operator nothing the first line did not.
     */
    private void reportMismatch(final JournalExchange exchange, final BodyKind kind, final BodyChecksum journaled, final BodyChecksum observed)
    {
        // Counted after the call, like the completion counters: a consumer that refuses the
        // report gets the whole end event offered again, and a counter bumped on every
        // attempt would report more mismatches than a consumer was ever told about. The
        // report itself is repeated on the retry, deliberately — a consumer that threw may
        // well not have recorded the first one.
        output.onChecksumMismatch(exchange, kind, journaled, observed);
        final long total = checksumMismatches.incrementAndGet();

        if (total == 1)
        {
            // BodyChecksum prints "not recorded" for an absent one, so a reader of this line
            // never has to work out whether "0" means the empty checksum or nothing at all.
            logger.error("{} body checksum mismatch for {}: journal recorded {} but the stored body checksums to {}. "
                            + "Further mismatches are counted, not logged; see getChecksumMismatchCount().",
                    kind, exchange.getRequestId(), journaled, observed);
        }
        else if (logger.isDebugEnabled())
        {
            logger.debug("{} body checksum mismatch for {} (mismatch #{})", kind, exchange.getRequestId(), total);
        }
    }

    private void maybeSweep()
    {
        if (++eventsSinceSweep < options.sweepIntervalEvents())
        {
            return;
        }
        sweep();
    }

    /**
     * Runs the age sweep immediately, regardless of how many events have been seen.
     * <p>
     * The amortised sweep only advances while events are arriving, so a reader that goes
     * quiet would otherwise hold abandoned exchanges indefinitely — and never report
     * them. A caller that knows it has reached a natural pause (the tailer, at the end of
     * a tick) should call this so the age limit means what it says on an idle stream.
     */
    public void sweep()
    {
        eventsSinceSweep = 0;

        final long cutoff = System.nanoTime() - maxAgeNanos;
        final int evicted = inFlight.evictOlderThan(cutoff, e -> evictIncomplete(e, IncompleteReason.TIMED_OUT));

        if (evicted > 0)
        {
            logger.warn("Evicted {} exchanges with no EndExchange within {} — journal for those requests is incomplete.",
                    evicted, Duration.ofNanos(maxAgeNanos));
        }
    }

    /**
     * Makes room for one more in-flight exchange, if there is not already room.
     * <p>
     * Only ever called when a request id that is <em>not</em> being tracked is about to be
     * added. It used to sit in {@link #sweep()}, which runs on every event — so an event for
     * an exchange already in the map could trip the ceiling and evict it. At
     * {@code maxInFlight = 1} that was fatal rather than merely wasteful: the second event
     * of every exchange evicted the exchange it belonged to, and nothing could ever
     * complete.
     */
    private void makeRoomForNewExchange()
    {
        if (inFlight.size() < options.maxInFlight())
        {
            return;
        }

        sweep();

        if (inFlight.size() >= options.maxInFlight())
        {
            // Still over the ceiling after the age sweep. This drops everything currently
            // tracked, including exchanges seconds old that would have completed — a
            // blunt backstop against heap exhaustion, not a tuning mechanism. Seeing this
            // in the log means maxInFlight is too low or maxAge is too high.
            final int forced = inFlight.evictOlderThan(System.nanoTime(), e -> evictIncomplete(e, IncompleteReason.CAPACITY_EVICTED));
            logger.error("In-flight exchanges exceeded the ceiling of {}; force-evicted {} incomplete exchanges. "
                            + "Raise maxInFlight or lower maxAge.",
                    options.maxInFlight(), forced);
        }
    }

    private void evictIncomplete(final JournalExchange exchange, final IncompleteReason reason)
    {
        abandoned.incrementAndGet();
        output.onAbandoned(exchange, reason);
        if (logger.isDebugEnabled())
        {
            logger.debug("Abandoned incomplete exchange {}: {}", exchange.getRequestId(), reason);
        }
    }

    /**
     * Copies header metadata out of the reader's buffers.
     * <p>
     * The decoder hands over lazy FlatBuffer views, and those point into whatever the
     * reader is currently looking at: a mapped segment. An exchange outlives that —
     * it is held until its end event, which can be in a later segment, and a consumer may
     * hold a completed one for longer still. Keeping the view would let the headers
     * silently change contents when the next file is mapped, which is the same
     * defect the body fragments already had.
     * <p>
     * Like the body copy, this allocates on the reader side only; the gateway write path
     * is untouched.
     */
    private static GatewayHeaders copyOf(final GatewayHeaders headers)
    {
        if (headers == null)
        {
            return null;
        }

        final MutableFastGatewayHeaders copy = new MutableFastGatewayHeaders();
        headers.forEach((name, value) -> {
            if (name != null && value != null)
            {
                copy.add(name, value);
            }
        });
        return copy;
    }

    /**
     * As {@link #copyOf(GatewayHeaders)}, for the attribute view on the end event.
     */
    private static GatewayAttributes copyOf(final GatewayAttributes attributes)
    {
        if (attributes == null)
        {
            return null;
        }

        final FastGatewayAttributes copy = new FastGatewayAttributes();
        attributes.forEach((name, value) -> {
            if (name != null && value != null)
            {
                copy.add(name, value);
            }
        });
        return copy;
    }

    private JournalExchange getOrCreate(String id)
    {
        final JournalExchange existing = inFlight.get(id);
        if (existing != null)
        {
            maybeSweep();
            return existing;
        }

        maybeSweep();
        makeRoomForNewExchange();
        return inFlight.computeIfAbsent(id, JournalExchange::new);
    }

    private boolean validateExchangeExists(JournalExchange exchange, String id, BodyKind kind)
    {
        if (exchange == null)
        {
            // If we have body data but no metadata slice, we have a protocol/ordering violation
            orphanedBodies.incrementAndGet();
            output.onOrphanedBody(id, kind);
            logger.warn("Protocol Violation: Received {} body for ID {} but no Start event was recorded.", kind, id);
            return false;
        }
        return true;
    }

    private boolean isExchangeComplete(JournalExchange exchange)
    {
        // At minimum, we must have the original ClientRequest to know the Method/URI
        // and a status > 0 from the EndExchange event.
        return exchange.getClientRequestStartLine() != null && exchange.getStatus() > 0;
    }

    public int getInFlightCount()
    {
        return inFlight.size();
    }

    public long getCompletedCount()
    {
        return completed.get();
    }

    /**
     * Exchanges that reached their end event but were not usable as a complete record.
     */
    public long getIncompleteEndCount()
    {
        return incompleteEnds.get();
    }

    /**
     * Exchanges dropped without ever seeing an end event: aged out or force-evicted.
     */
    public long getAbandonedCount()
    {
        return abandoned.get();
    }

    public long getOrphanedEndCount()
    {
        return orphanedEnds.get();
    }

    public long getOrphanedBodyCount()
    {
        return orphanedBodies.get();
    }

    public long getChecksumMismatchCount()
    {
        return checksumMismatches.get();
    }
}
