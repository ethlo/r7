package com.ethlo.r7.warc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalExchange;

/**
 * Turns each completed {@link JournalExchange} into WARC records.
 * <p>
 * An exchange has up to two "capture events" — the client leg (client &lt;-&gt; r7) and, if
 * proxied, the upstream leg (r7 &lt;-&gt; upstream) — each written as a {@code request}/
 * {@code response} pair linked by {@code WARC-Concurrent-To}. The two legs share the same
 * {@code WARC-R7-Request-Id} extension field so a reader can still associate them, without
 * conflating them into one {@code WARC-Concurrent-To} group: they really are two distinct
 * retrievals with (in general) two distinct target URIs.
 * <p>
 * <b>Deduplication.</b> {@code JournalExchange} stores exactly one copy of the request body and
 * one copy of the response body — not one per leg — so the client-request/upstream-request
 * payload and the upstream-response/client-response payload are structurally guaranteed
 * identical; there is nothing to compare. That fact is exploited by the same mechanism used for
 * incidental cross-exchange duplicates (repeated static assets, cached responses): a bounded
 * {@link PayloadDedupIndex} keyed by payload digest. The first record needing a given payload in
 * this process's lifetime is written in full and remembered; every subsequent record needing the
 * same payload — whether that's the upstream leg of the very same exchange or a different
 * exchange entirely — is written as a {@code revisit} record per the WARC 1.1
 * {@code identical-payload-digest} profile, headers preserved, payload omitted.
 */
public final class WarcExchangeWriter implements ExchangeCompletionListener
{
    private static final Logger logger = LoggerFactory.getLogger(WarcExchangeWriter.class);

    private final WarcFileWriter fileWriter;
    private final PayloadDedupIndex dedupIndex;

    public WarcExchangeWriter(final WarcFileWriter fileWriter, final PayloadDedupIndex dedupIndex)
    {
        this.fileWriter = fileWriter;
        this.dedupIndex = dedupIndex;
    }

    @Override
    public void onComplete(final JournalExchange exchange)
    {
        try
        {
            final String requestDigest = PayloadDigest.of(exchange.getRequestBodyFragments());
            final String responseDigest = PayloadDigest.of(exchange.getResponseBodyFragments());

            writePair(exchange, requestDigest, responseDigest,
                    exchange.getClientRequestStartLine(), exchange.getClientRequestHeaders(),
                    exchange.getClientResponseStartLine(), exchange.getClientResponseHeaders(),
                    exchange.getRequestBodyFragments(), exchange.getResponseBodyFragments(),
                    exchange.remoteAddress());

            if (exchange.wasProxied())
            {
                writePair(exchange, requestDigest, responseDigest,
                        exchange.getUpstreamRequestStartLine(), exchange.getUpstreamRequestHeaders(),
                        exchange.getUpstreamResponseStartLine(), exchange.getUpstreamResponseHeaders(),
                        exchange.getRequestBodyFragments(), exchange.getResponseBodyFragments(),
                        null);
            }
        }
        catch (final IOException e)
        {
            throw new UncheckedIOException("Failed to write WARC records for exchange " + exchange.getRequestId(), e);
        }
    }

    @Override
    public void onIncomplete(final JournalExchange exchange, final ExchangeCompletionListener.IncompleteReason reason)
    {
        logger.warn("Skipping incomplete exchange {}: {}", exchange.getRequestId(), reason);
    }

    private void writePair(final JournalExchange exchange, final String requestDigest, final String responseDigest,
                            final String reqStartLine, final GatewayHeaders reqHeaders,
                            final String respStartLine, final GatewayHeaders respHeaders,
                            final List<ByteBuffer> requestBody, final List<ByteBuffer> responseBody,
                            final InetAddress clientAddress) throws IOException
    {
        if (reqStartLine == null && respStartLine == null)
        {
            // Nothing was journaled for this leg at all (JournalLevel.NONE) - no record to write.
            return;
        }

        final String requestId = exchange.getRequestId();
        final GatewayHeaders headersForUri = reqHeaders != null ? reqHeaders : respHeaders;
        final String targetUri = TargetUri.build(reqStartLine, headersForUri, requestId);

        final String reqRecordId = WarcFields.newRecordId();
        final String respRecordId = WarcFields.newRecordId();

        if (reqStartLine != null)
        {
            writeMessageRecord("request", reqRecordId, respRecordId, targetUri, requestId,
                    reqStartLine, reqHeaders, requestBody, requestDigest, clientAddress);
        }
        if (respStartLine != null)
        {
            writeMessageRecord("response", respRecordId, reqRecordId, targetUri, requestId,
                    respStartLine, respHeaders, responseBody, responseDigest, clientAddress);
        }
    }

    private void writeMessageRecord(final String msgType, final String ownRecordId, final String concurrentToRecordId,
                                     final String targetUri, final String requestId,
                                     final String startLine, final GatewayHeaders headers,
                                     final List<ByteBuffer> body, final String payloadDigest,
                                     final InetAddress clientAddress) throws IOException
    {
        final PayloadDedupIndex.RevisitTarget existing = payloadDigest != null ? dedupIndex.find(payloadDigest) : null;
        if (existing != null)
        {
            final byte[] block = HttpMessageBlock.headersOnly(startLine, headers);
            final Map<String, String> fields = WarcFields.revisit(msgType, targetUri, concurrentToRecordId, requestId, payloadDigest, existing);
            addClientAddress(fields, clientAddress);
            fileWriter.writeRecord(ownRecordId, "revisit", fields, block);
            return;
        }

        final byte[] block = payloadDigest != null
                ? HttpMessageBlock.withBody(startLine, headers, body)
                : HttpMessageBlock.headersOnly(startLine, headers);
        final Map<String, String> fields = WarcFields.requestOrResponse(msgType, targetUri, concurrentToRecordId, requestId, payloadDigest);
        addClientAddress(fields, clientAddress);
        final String warcDate = fields.get("WARC-Date");
        fileWriter.writeRecord(ownRecordId, msgType, fields, block);

        if (payloadDigest != null)
        {
            dedupIndex.remember(payloadDigest, new PayloadDedupIndex.RevisitTarget(ownRecordId, targetUri, warcDate));
        }
    }

    private static void addClientAddress(final Map<String, String> fields, final InetAddress clientAddress)
    {
        // Not WARC-IP-Address: that field means "the server contacted to retrieve the payload",
        // which for the client leg is r7 itself, not the caller. This is a plain extension field.
        if (clientAddress != null)
        {
            fields.put("WARC-R7-Client-IP", clientAddress.getHostAddress());
        }
    }
}
