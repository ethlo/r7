package com.ethlo.r7.warc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalExchange;

/**
 * Turns each completed {@link JournalExchange} into WARC records, per {@code design/warc.md}.
 * <p>
 * <b>Four records, in the order they occurred</b> (client request, upstream request, upstream
 * response, client response), linked to each other by repeated {@code WARC-Concurrent-To}
 * fields — the one WARC field explicitly permitted to repeat within a record. The gateway
 * journal itself stores exactly one copy of the request body and one copy of the response
 * body, not one per network leg, so records 2 and 3 (the upstream leg) never have bytes of
 * their own: they carry {@code WARC-Truncated: unspecified} plus the
 * {@code WARC-Payload-Digest} of the payload records 1/4 actually store, which together with
 * {@code WARC-Concurrent-To} is enough for a reader to resolve the payload without
 * r7-specific knowledge. This is deliberately not a {@code revisit} record: {@code revisit}
 * means "content unchanged since it was archived", which the second hop of one exchange is
 * not, and it would also hide the forwarded request from a reader scanning for
 * {@code WARC-Type: request}.
 * <p>
 * <b>Cross-exchange deduplication</b> is a separate, genuine case {@code design/warc.md} does
 * not address: two different exchanges (a repeated static asset, a cached response)
 * legitimately producing byte-identical payloads. Only records 1 and 4 - the ones that ever
 * own a payload - participate in it, via a bounded {@link PayloadDedupIndex} keyed by payload
 * digest; the first record needing a given payload in this process's lifetime is written in
 * full and remembered, and a later one for a genuinely different exchange is written as a
 * {@code revisit} per the WARC 1.1 {@code identical-payload-digest} profile. The index is only
 * ever consulted or updated once a whole exchange group's records have been decided, so a hit
 * can never be this same exchange's own request/response body (which, at empty or otherwise
 * identical payloads, would otherwise be a false intra-exchange match).
 * <p>
 * <b>Atomicity</b>: every leg's frame is built in memory first; the whole group is handed to
 * {@link WarcFileWriter#writeRecords} as one call, which either writes all of it or none of
 * it. {@code R7Tailer} retries a whole exchange when this listener throws, so this is what
 * keeps a failure partway through a group from leaving some of its records durably written and
 * others not - a retry would otherwise re-decide and re-write the group under new record IDs,
 * duplicating whatever had already landed.
 */
public final class WarcExchangeWriter implements ExchangeCompletionListener
{
    private static final Logger logger = LoggerFactory.getLogger(WarcExchangeWriter.class);

    private final WarcFileWriter fileWriter;
    private final PayloadDedupIndex dedupIndex;

    /**
     * Body kinds {@link #onChecksumMismatch} reported for the exchange currently being
     * completed, keyed by request id. Populated here and consumed/cleared in {@link #onComplete}
     * for the same exchange: {@code R7Tailer}'s reassembler calls both on the same thread, for
     * the same exchange, in the same reassembly step, so no further synchronization is needed —
     * {@link ConcurrentHashMap} is cheap insurance against that assumption changing, not a
     * requirement of it.
     */
    private final Map<String, Set<BodyKind>> checksumMismatches = new ConcurrentHashMap<>();

    public WarcExchangeWriter(final WarcFileWriter fileWriter, final PayloadDedupIndex dedupIndex)
    {
        this.fileWriter = fileWriter;
        this.dedupIndex = dedupIndex;
    }

    @Override
    public void onChecksumMismatch(final JournalExchange exchange, final BodyKind kind, final BodyChecksum journaled, final BodyChecksum observed)
    {
        logger.warn("Checksum mismatch for {} body of exchange {}: journaled={}, observed={} - archiving without payload",
                kind, exchange.getRequestId(), journaled, observed);
        checksumMismatches.computeIfAbsent(exchange.getRequestId(), id -> EnumSet.noneOf(BodyKind.class)).add(kind);
    }

    @Override
    public void onComplete(final JournalExchange exchange)
    {
        try
        {
            final Set<BodyKind> mismatched = checksumMismatches.remove(exchange.getRequestId());
            final boolean requestMismatch = mismatched != null && mismatched.contains(BodyKind.REQUEST);
            final boolean responseMismatch = mismatched != null && mismatched.contains(BodyKind.RESPONSE);

            final String requestDigest = requestMismatch ? null : PayloadDigest.of(exchange.getRequestBodyFragments());
            final String responseDigest = responseMismatch ? null : PayloadDigest.of(exchange.getResponseBodyFragments());

            writeExchangeGroup(exchange, requestDigest, responseDigest, requestMismatch, responseMismatch);
        }
        catch (final IOException e)
        {
            throw new UncheckedIOException("Failed to write WARC records for exchange " + exchange.getRequestId(), e);
        }
    }

    @Override
    public void onIncomplete(final JournalExchange exchange, final ExchangeCompletionListener.IncompleteReason reason)
    {
        checksumMismatches.remove(exchange.getRequestId());
        logger.warn("Skipping incomplete exchange {}: {}", exchange.getRequestId(), reason);
    }

    /**
     * A single leg (start line + headers), one of the up-to-four records this exchange emits.
     *
     * @param bodyBytesReported the byte count the exchange's traffic counters report for this
     *                          leg's body, regardless of journal level — {@code 0} both for a
     *                          genuinely empty body and for a leg this record doesn't own; used
     *                          only to tell "no body" apart from "body not captured" when
     *                          {@code payloadDigest} is {@code null}
     */
    private record Leg(String msgType, String startLine, GatewayHeaders headers, boolean bodyOwner,
                       List<ByteBuffer> body, String payloadDigest, long bodyBytesReported,
                       boolean checksumMismatch, InetAddress clientAddress)
    {
    }

    private void writeExchangeGroup(final JournalExchange exchange, final String requestDigest, final String responseDigest,
                                     final boolean requestMismatch, final boolean responseMismatch) throws IOException
    {
        final String requestId = exchange.getRequestId();

        // Client-facing and upstream-facing URIs can differ (path rewriting, host substitution),
        // so each hop resolves its own target URI rather than sharing one across the exchange.
        final String clientStartLine = exchange.getClientRequestStartLine();
        final GatewayHeaders clientUriHeaders = clientStartLine != null ? exchange.getClientRequestHeaders() : exchange.getClientResponseHeaders();
        final String clientTargetUri = TargetUri.build(clientStartLine, clientUriHeaders, requestId);

        final String upstreamStartLine = exchange.getUpstreamRequestStartLine();
        final GatewayHeaders upstreamUriHeaders = upstreamStartLine != null ? exchange.getUpstreamRequestHeaders() : exchange.getUpstreamResponseHeaders();
        final String upstreamTargetUri = TargetUri.build(upstreamStartLine, upstreamUriHeaders, requestId);

        // In the order they occurred: client request, upstream request, upstream response, client response.
        final List<Leg> legs = new ArrayList<>(4);
        final List<String> targetUris = new ArrayList<>(4);
        if (addIfJournaled(legs, "request", clientStartLine, exchange.getClientRequestHeaders(),
                true, exchange.getRequestBodyFragments(), requestDigest, exchange.getRequestBodyBytes(), requestMismatch, exchange.remoteAddress()))
        {
            targetUris.add(clientTargetUri);
        }
        if (exchange.wasProxied())
        {
            if (addIfJournaled(legs, "request", upstreamStartLine, exchange.getUpstreamRequestHeaders(),
                    false, null, requestDigest, 0, requestMismatch, null))
            {
                targetUris.add(upstreamTargetUri);
            }
            if (addIfJournaled(legs, "response", exchange.getUpstreamResponseStartLine(), exchange.getUpstreamResponseHeaders(),
                    false, null, responseDigest, 0, responseMismatch, null))
            {
                targetUris.add(upstreamTargetUri);
            }
        }
        if (addIfJournaled(legs, "response", exchange.getClientResponseStartLine(), exchange.getClientResponseHeaders(),
                true, exchange.getResponseBodyFragments(), responseDigest, exchange.getResponseBodyBytes(), responseMismatch, exchange.remoteAddress()))
        {
            targetUris.add(clientTargetUri);
        }

        if (legs.isEmpty())
        {
            // Nothing was journaled for this exchange at all (JournalLevel.NONE) - no record to write.
            return;
        }

        final String[] recordIds = new String[legs.size()];
        for (int i = 0; i < legs.size(); i++)
        {
            recordIds[i] = WarcFields.newRecordId();
        }

        // Every frame is built and staged here first; nothing is written to disk or remembered
        // in the dedup index until the whole group has been assembled. This is what keeps a
        // thrown exception from ever leaving a partial group on disk (R7Tailer retries the
        // whole exchange on failure - see WarcFileWriter#writeRecords), and it is also what
        // keeps a dedup lookup for a later leg in this same group from ever matching a payload
        // this same group already wrote: remembers only happen after every leg has been decided.
        final List<WarcFileWriter.PendingRecord> pending = new ArrayList<>(legs.size());
        final List<Runnable> dedupRemembers = new ArrayList<>(2);
        for (int i = 0; i < legs.size(); i++)
        {
            final List<String> concurrentToIds = new ArrayList<>(legs.size() - 1);
            for (int j = 0; j < legs.size(); j++)
            {
                if (j != i)
                {
                    concurrentToIds.add(recordIds[j]);
                }
            }
            pending.add(prepareRecord(legs.get(i), recordIds[i], concurrentToIds, targetUris.get(i), requestId, dedupRemembers));
        }

        fileWriter.writeRecords(pending);

        for (final Runnable remember : dedupRemembers)
        {
            remember.run();
        }
    }

    private static boolean addIfJournaled(final List<Leg> legs, final String msgType, final String startLine, final GatewayHeaders headers,
                                           final boolean bodyOwner, final List<ByteBuffer> body, final String payloadDigest,
                                           final long bodyBytesReported, final boolean checksumMismatch, final InetAddress clientAddress)
    {
        if (startLine != null)
        {
            legs.add(new Leg(msgType, startLine, headers, bodyOwner, body, payloadDigest, bodyBytesReported, checksumMismatch, clientAddress));
            return true;
        }
        return false;
    }

    /**
     * Decides the record type for one leg and builds its frame contents, without writing
     * anything or touching the dedup index — see {@link #writeExchangeGroup} for why both are
     * deferred until the whole group is ready.
     */
    private WarcFileWriter.PendingRecord prepareRecord(final Leg leg, final String ownRecordId, final List<String> concurrentToIds,
                                                        final String targetUri, final String requestId, final List<Runnable> dedupRemembers)
    {
        if (leg.checksumMismatch())
        {
            final byte[] block = HttpMessageBlock.headersOnly(leg.startLine(), leg.headers());
            final List<Map.Entry<String, String>> fields = WarcFields.checksumMismatch(leg.msgType(), targetUri, concurrentToIds, requestId);
            addClientAddress(fields, leg.clientAddress());
            return new WarcFileWriter.PendingRecord(ownRecordId, leg.msgType(), fields, block);
        }

        if (!leg.bodyOwner())
        {
            // The upstream leg: byte-identical to, and already stored by, the client leg of the
            // same direction - see the class javadoc for why this is not a revisit record.
            final byte[] block = HttpMessageBlock.headersOnly(leg.startLine(), leg.headers());
            final List<Map.Entry<String, String>> fields = WarcFields.notStoredElsewhere(leg.msgType(), targetUri, concurrentToIds, requestId, leg.payloadDigest());
            addClientAddress(fields, leg.clientAddress());
            return new WarcFileWriter.PendingRecord(ownRecordId, leg.msgType(), fields, block);
        }

        final String payloadDigest = leg.payloadDigest();

        if (payloadDigest == null && leg.bodyBytesReported() > 0)
        {
            // A body crossed the wire (non-zero traffic count) but nothing was captured for it -
            // the journal level for this route/direction is below FULL. Without WARC-Truncated
            // this would look like a message that genuinely had no body at all.
            final byte[] block = HttpMessageBlock.headersOnly(leg.startLine(), leg.headers());
            final List<Map.Entry<String, String>> fields = WarcFields.notCaptured(leg.msgType(), targetUri, concurrentToIds, requestId);
            addClientAddress(fields, leg.clientAddress());
            return new WarcFileWriter.PendingRecord(ownRecordId, leg.msgType(), fields, block);
        }

        // Cross-exchange dedup only: the index is never consulted or updated for any leg of the
        // exchange currently being written until this whole group has been durably written (see
        // writeExchangeGroup), so a hit here can only ever be a genuinely earlier exchange.
        final PayloadDedupIndex.RevisitTarget existing = payloadDigest != null ? dedupIndex.find(payloadDigest) : null;
        if (existing != null)
        {
            final byte[] block = HttpMessageBlock.headersOnly(leg.startLine(), leg.headers());
            final List<Map.Entry<String, String>> fields = WarcFields.revisit(leg.msgType(), targetUri, concurrentToIds, requestId, payloadDigest, existing);
            addClientAddress(fields, leg.clientAddress());
            return new WarcFileWriter.PendingRecord(ownRecordId, "revisit", fields, block);
        }

        final byte[] block = payloadDigest != null
                ? HttpMessageBlock.withBody(leg.startLine(), leg.headers(), leg.body())
                : HttpMessageBlock.headersOnly(leg.startLine(), leg.headers());
        final List<Map.Entry<String, String>> fields = WarcFields.stored(leg.msgType(), targetUri, concurrentToIds, requestId, payloadDigest);
        addClientAddress(fields, leg.clientAddress());

        if (payloadDigest != null)
        {
            final String warcDate = fieldValue(fields, "WARC-Date");
            dedupRemembers.add(() -> dedupIndex.remember(payloadDigest, new PayloadDedupIndex.RevisitTarget(ownRecordId, targetUri, warcDate)));
        }

        return new WarcFileWriter.PendingRecord(ownRecordId, leg.msgType(), fields, block);
    }

    private static String fieldValue(final List<Map.Entry<String, String>> fields, final String key)
    {
        for (final Map.Entry<String, String> field : fields)
        {
            if (field.getKey().equals(key))
            {
                return field.getValue();
            }
        }
        return null;
    }

    private static void addClientAddress(final List<Map.Entry<String, String>> fields, final InetAddress clientAddress)
    {
        // Not WARC-IP-Address: that field means "the server contacted to retrieve the payload",
        // which for the client leg is r7 itself, not the caller. This is a plain extension field.
        if (clientAddress != null)
        {
            fields.add(Map.entry("WARC-R7-Client-IP", clientAddress.getHostAddress()));
        }
    }
}
