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
 * {@code revisit} per the WARC 1.1 {@code identical-payload-digest} profile.
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
     */
    private record Leg(String msgType, String startLine, GatewayHeaders headers, boolean bodyOwner,
                       List<ByteBuffer> body, String payloadDigest, boolean checksumMismatch, InetAddress clientAddress)
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
                true, exchange.getRequestBodyFragments(), requestDigest, requestMismatch, exchange.remoteAddress()))
        {
            targetUris.add(clientTargetUri);
        }
        if (exchange.wasProxied())
        {
            if (addIfJournaled(legs, "request", upstreamStartLine, exchange.getUpstreamRequestHeaders(),
                    false, null, requestDigest, requestMismatch, null))
            {
                targetUris.add(upstreamTargetUri);
            }
            if (addIfJournaled(legs, "response", exchange.getUpstreamResponseStartLine(), exchange.getUpstreamResponseHeaders(),
                    false, null, responseDigest, responseMismatch, null))
            {
                targetUris.add(upstreamTargetUri);
            }
        }
        if (addIfJournaled(legs, "response", exchange.getClientResponseStartLine(), exchange.getClientResponseHeaders(),
                true, exchange.getResponseBodyFragments(), responseDigest, responseMismatch, exchange.remoteAddress()))
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
            writeMessageRecord(legs.get(i), recordIds[i], concurrentToIds, targetUris.get(i), requestId);
        }
    }

    private static boolean addIfJournaled(final List<Leg> legs, final String msgType, final String startLine, final GatewayHeaders headers,
                                           final boolean bodyOwner, final List<ByteBuffer> body, final String payloadDigest,
                                           final boolean checksumMismatch, final InetAddress clientAddress)
    {
        if (startLine != null)
        {
            legs.add(new Leg(msgType, startLine, headers, bodyOwner, body, payloadDigest, checksumMismatch, clientAddress));
            return true;
        }
        return false;
    }

    private void writeMessageRecord(final Leg leg, final String ownRecordId, final List<String> concurrentToIds,
                                     final String targetUri, final String requestId) throws IOException
    {
        if (leg.checksumMismatch())
        {
            final byte[] block = HttpMessageBlock.headersOnly(leg.startLine(), leg.headers());
            final List<Map.Entry<String, String>> fields = WarcFields.checksumMismatch(leg.msgType(), targetUri, concurrentToIds, requestId);
            addClientAddress(fields, leg.clientAddress());
            fileWriter.writeRecord(ownRecordId, leg.msgType(), fields, block);
            return;
        }

        if (!leg.bodyOwner())
        {
            // The upstream leg: byte-identical to, and already stored by, the client leg of the
            // same direction - see the class javadoc for why this is not a revisit record.
            final byte[] block = HttpMessageBlock.headersOnly(leg.startLine(), leg.headers());
            final List<Map.Entry<String, String>> fields = WarcFields.notStoredElsewhere(leg.msgType(), targetUri, concurrentToIds, requestId, leg.payloadDigest());
            addClientAddress(fields, leg.clientAddress());
            fileWriter.writeRecord(ownRecordId, leg.msgType(), fields, block);
            return;
        }

        final String payloadDigest = leg.payloadDigest();
        final PayloadDedupIndex.RevisitTarget existing = payloadDigest != null ? dedupIndex.find(payloadDigest) : null;
        if (existing != null)
        {
            final byte[] block = HttpMessageBlock.headersOnly(leg.startLine(), leg.headers());
            final List<Map.Entry<String, String>> fields = WarcFields.revisit(leg.msgType(), targetUri, concurrentToIds, requestId, payloadDigest, existing);
            addClientAddress(fields, leg.clientAddress());
            fileWriter.writeRecord(ownRecordId, "revisit", fields, block);
            return;
        }

        final byte[] block = payloadDigest != null
                ? HttpMessageBlock.withBody(leg.startLine(), leg.headers(), leg.body())
                : HttpMessageBlock.headersOnly(leg.startLine(), leg.headers());
        final List<Map.Entry<String, String>> fields = WarcFields.stored(leg.msgType(), targetUri, concurrentToIds, requestId, payloadDigest);
        addClientAddress(fields, leg.clientAddress());
        final String warcDate = fieldValue(fields, "WARC-Date");
        fileWriter.writeRecord(ownRecordId, leg.msgType(), fields, block);

        if (payloadDigest != null)
        {
            dedupIndex.remember(payloadDigest, new PayloadDedupIndex.RevisitTarget(ownRecordId, targetUri, warcDate));
        }
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
