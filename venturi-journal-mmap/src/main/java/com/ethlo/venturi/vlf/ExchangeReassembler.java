package com.ethlo.venturi.vlf;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

import com.ethlo.venturi.api.IpSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.journal.api.ExchangeCompletionListener;
import com.ethlo.venturi.journal.api.JournalExchange;
import com.ethlo.venturi.journal.api.JournalLevel;
import com.ethlo.venturi.vlf.util.CharSequenceExchangeMap;

public class ExchangeReassembler implements JournalEventListener
{
    private static final Logger logger = LoggerFactory.getLogger(ExchangeReassembler.class);

    // Using your optimized map for high-concurrency reassembly
    private final CharSequenceExchangeMap inFlight = new CharSequenceExchangeMap(10_000);

    private final ExchangeCompletionListener output;
    private final CRC32C requestCrc32 = new CRC32C();
    private final CRC32C responseCrc32 = new CRC32C();

    public ExchangeReassembler(ExchangeCompletionListener output)
    {
        this.output = output;
    }

    @Override
    public void onClientRequest(CharSequence reqId, JournalLevel level, CharSequence startLine, GatewayHeaders headers, InetAddress remoteAddress, IpSource ipSource)
    {
        getOrCreate(reqId).setClientRequest(startLine, level, headers, remoteAddress, ipSource);
    }

    @Override
    public void onUpstreamRequest(CharSequence reqId, JournalLevel level, CharSequence startLine, GatewayHeaders headers)
    {
        getOrCreate(reqId).setUpstreamRequest(startLine, level, headers);
    }

    @Override
    public void onUpstreamResponse(CharSequence reqId, JournalLevel level, CharSequence startLine, GatewayHeaders headers)
    {
        getOrCreate(reqId).setUpstreamResponse(startLine, level, headers);
    }

    @Override
    public void onClientResponse(CharSequence reqId, JournalLevel level, CharSequence startLine, GatewayHeaders headers)
    {
        getOrCreate(reqId).setClientResponse(startLine, level, headers);
    }

    @Override
    public void onRequestBody(CharSequence reqId, ByteBuffer bodyChunk)
    {
        final JournalExchange exchange = inFlight.get(reqId);
        if (validateExchangeExists(exchange, reqId, "REQUEST_BODY"))
        {
            requestCrc32.update(bodyChunk.duplicate());
            exchange.appendRequestBody(bodyChunk);
        }
    }

    @Override
    public void onResponseBody(CharSequence reqId, ByteBuffer bodyChunk)
    {
        final JournalExchange exchange = inFlight.get(reqId);
        if (validateExchangeExists(exchange, reqId, "RESPONSE_BODY"))
        {
            responseCrc32.update(bodyChunk.duplicate());
            exchange.appendResponseBody(bodyChunk);
        }
    }

    @Override
    public void onEnd(CharSequence reqId, GatewayAttributes attributes,
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

        if (isExchangeComplete(exchange))
        {
            output.onComplete(exchange);
        }
    }

    private JournalExchange getOrCreate(CharSequence id)
    {
        return inFlight.computeIfAbsent(id, JournalExchange::new);
    }

    private boolean validateExchangeExists(JournalExchange exchange, CharSequence id, String type)
    {
        if (exchange == null)
        {
            // If we have body data but no metadata slice, we have a protocol/ordering violation
            logger.warn("Protocol Violation: Received {} for ID {} but no Start event was recorded.", type, id);
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
}