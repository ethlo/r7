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
                      final int requestCrc32, final int responseCrc32c)
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
        exchange.setJournalChecksums(requestCrc32, responseCrc32c);

        verifyChecksums(exchange, requestCrc32, responseCrc32c);

        if (isExchangeComplete(exchange))
        {
            completed.incrementAndGet();
            output.onComplete(exchange);
        }
        else
        {
            final IncompleteReason reason = exchange.getClientRequestStartLine() == null
                    ? IncompleteReason.NO_START_EVENT
                    : IncompleteReason.NO_STATUS;
            incompleteEnds.incrementAndGet();
            output.onIncompleteEnd(exchange, reason);
            logger.warn("Exchange {} ended but is not a complete record: {}", reqId, reason);
        }

        maybeSweep();
    }

    /**
     * Compares what the gateway recorded against what was actually read back. A mismatch
     * means the body bytes in the journal are not the bytes that crossed the wire, which
     * is exactly what an audit trail exists to rule out.
     */
    private void verifyChecksums(final JournalExchange exchange, final int journaledRequestCrc, final int journaledResponseCrc)
    {
        // Whether a body should be there is decided by the journal level and the byte
        // count, never by the checksum value: CRC32C of a non-empty body is legitimately
        // zero for some inputs, so treating zero as "no body" would wave through exactly
        // the exchanges whose bodies hash that way.
        //
        // Whether a checksum was *recorded* is a separate question, and one the writer has
        // to answer explicitly for the same reason — see CHECKSUM_NOT_RECORDED. The
        // gateway records that value for every exchange today, so this comparison is
        // dormant in production until it computes checksums.
        if (journaledRequestCrc != JournalExchange.CHECKSUM_NOT_RECORDED
                && bodyWasJournaled(exchange.getClientRequestLevel(), exchange.getRequestBodyBytes()))
        {
            final Integer observed = exchange.getObservedRequestCrc32();
            if (observed == null || observed.intValue() != journaledRequestCrc)
            {
                reportMismatch(exchange, BodyKind.REQUEST, journaledRequestCrc, observed);
            }
        }

        if (journaledResponseCrc != JournalExchange.CHECKSUM_NOT_RECORDED
                && bodyWasJournaled(exchange.getClientResponseLevel(), exchange.getResponseBodyBytes()))
        {
            final Integer observed = exchange.getObservedResponseCrc32();
            if (observed == null || observed.intValue() != journaledResponseCrc)
            {
                reportMismatch(exchange, BodyKind.RESPONSE, journaledResponseCrc, observed);
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
    private void reportMismatch(final JournalExchange exchange, final BodyKind kind, final int journaled, final Integer observed)
    {
        final long total = checksumMismatches.incrementAndGet();
        output.onChecksumMismatch(exchange, kind, journaled, observed == null ? 0 : observed);

        if (total == 1)
        {
            logger.error("{} body checksum mismatch for {}: journal recorded {} but the stored body checksums to {}. "
                            + "Further mismatches are counted, not logged; see getChecksumMismatchCount().",
                    kind, exchange.getRequestId(), journaled, observed == null ? "nothing at all" : observed);
        }
        else if (logger.isDebugEnabled())
        {
            logger.debug("{} body checksum mismatch for {} (mismatch #{})", kind, exchange.getRequestId(), total);
        }
    }

    /**
     * Whether the journal should hold a body for this half of the exchange: bodies are
     * only written at {@link JournalLevel#FULL}, and only when there were any bytes.
     * A null level means the corresponding start event was never seen, so there is
     * nothing to check against.
     */
    private static boolean bodyWasJournaled(final JournalLevel level, final long bodyBytes)
    {
        return level == JournalLevel.FULL && bodyBytes > 0;
    }

    private void maybeSweep()
    {
        if (++eventsSinceSweep < options.sweepIntervalEvents() && inFlight.size() < options.maxInFlight())
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
        else if (evicted > 0)
        {
            logger.warn("Evicted {} exchanges with no EndExchange within {} — journal for those requests is incomplete.",
                    evicted, Duration.ofNanos(maxAgeNanos));
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
     * The decoder hands over lazy FlatBuffer views, and for a compressed segment those
     * point into the tailer's reusable decompression buffer. An exchange outlives that —
     * it is held until its end event, which can be in a later segment, and a consumer may
     * hold a completed one for longer still. Keeping the view would let the headers
     * silently change contents when the next file is decompressed, which is the same
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
        maybeSweep();
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
