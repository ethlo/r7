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
        getOrCreate(reqId).setClientRequest(startLine, level, headers, remoteAddress, ipSource);
    }

    @Override
    public void onUpstreamRequest(String reqId, JournalLevel level, String startLine, GatewayHeaders headers)
    {
        getOrCreate(reqId).setUpstreamRequest(startLine, level, headers);
    }

    @Override
    public void onUpstreamResponse(String reqId, JournalLevel level, String startLine, GatewayHeaders headers)
    {
        getOrCreate(reqId).setUpstreamResponse(startLine, level, headers);
    }

    @Override
    public void onClientResponse(String reqId, JournalLevel level, String startLine, GatewayHeaders headers)
    {
        getOrCreate(reqId).setClientResponse(startLine, level, headers);
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
        exchange.setAttributes(attributes);
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
        // A non-zero journaled checksum means the gateway saw a body. If none was read
        // back, every body entry for this exchange is missing — which is a worse failure
        // than a corrupted one, and skipping the check because there is nothing to compare
        // would let the exchange through as complete with its body silently absent.
        final Integer observedRequest = exchange.getObservedRequestCrc32();
        if (journaledRequestCrc != 0 && (observedRequest == null || observedRequest != journaledRequestCrc))
        {
            checksumMismatches.incrementAndGet();
            output.onChecksumMismatch(exchange, BodyKind.REQUEST, journaledRequestCrc, observedRequest == null ? 0 : observedRequest);
            logger.error("Request body checksum mismatch for {}: journal recorded {} but the stored body checksums to {}",
                    exchange.getRequestId(), journaledRequestCrc, observedRequest == null ? "nothing at all" : observedRequest);
        }

        final Integer observedResponse = exchange.getObservedResponseCrc32();
        if (journaledResponseCrc != 0 && (observedResponse == null || observedResponse != journaledResponseCrc))
        {
            checksumMismatches.incrementAndGet();
            output.onChecksumMismatch(exchange, BodyKind.RESPONSE, journaledResponseCrc, observedResponse == null ? 0 : observedResponse);
            logger.error("Response body checksum mismatch for {}: journal recorded {} but the stored body checksums to {}",
                    exchange.getRequestId(), journaledResponseCrc, observedResponse == null ? "nothing at all" : observedResponse);
        }
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
