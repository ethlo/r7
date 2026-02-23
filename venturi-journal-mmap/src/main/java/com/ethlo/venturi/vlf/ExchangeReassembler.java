package com.ethlo.venturi.vlf;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.journal.api.ExchangeCompletionListener;
import com.ethlo.venturi.journal.api.JournalExchange;
import com.ethlo.venturi.api.ServerDirection;

public class ExchangeReassembler implements JournalEventListener
{
    private static final Logger logger = LoggerFactory.getLogger(ExchangeReassembler.class);
    private final Map<String, JournalExchange> inFlight = new HashMap<>();
    private final ExchangeCompletionListener output;

    public ExchangeReassembler(ExchangeCompletionListener output)
    {
        this.output = output;
    }

    @Override
    public void onBegin(ServerDirection dir, String id, String line, Map<CharSequence, CharSequence> headers)
    {
        JournalExchange exchange = inFlight.computeIfAbsent(id, JournalExchange::new);
        if (dir == ServerDirection.REQUEST)
        {
            exchange.setRequest(line, headers);
        }
        else
        {
            exchange.setResponse(line, headers);
        }

        if (isExchangeComplete(exchange))
        {
            inFlight.remove(id);
            output.onComplete(exchange);
        }
    }

    private boolean isExchangeComplete(JournalExchange exchange)
    {
        // 1. Did we see the REQUEST Begin? (Required for URL/Method)
        boolean hasRequest = exchange.getRequestStartLine() != null;

        // 2. Did we see the END marker? (Required for Status/Latency)
        boolean hasMetrics = exchange.getStatus() > 0;

        return hasRequest && hasMetrics;
    }

    @Override
    public void onBody(ServerDirection dir, String id, ByteBuffer body)
    {
        JournalExchange exchange = inFlight.get(id);
        if (exchange == null)
        {
            // CRITICAL: If we have a body but no begin, we are out of sync
            throw new IllegalStateException("Protocol Violation: Received BODY for ID " + id + " but no BEGIN event was recorded for this exchange.");
        }
        exchange.appendBody(dir, body);
    }

    @Override
    public void onEnd(String requestId, long ts, int status, long sent, long recv, long dur)
    {
        JournalExchange exchange = inFlight.get(requestId);

        if (exchange == null)
        {
            // Missing BEGIN is expected at startup or shard boundaries.
            // We log it and RETURN so the Tailer can keep moving.
            if (logger.isDebugEnabled())
            {
                logger.debug("Received END for {} but no BEGIN exists. Skipping orphan.", requestId);
            }
            return;
        }

        // Set metrics. We don't care if RequestStartLine is null here;
        // it might be in a different shard we haven't read yet this tick.
        exchange.setMetrics(ts, status, sent, recv, dur);

        // Logic for completion: If we have what we need, move it to output.
        // If not, we leave it in inFlight. The BEGIN handler will pick it up
        // when it eventually finds the other half of the data.
        if (isExchangeComplete(exchange))
        {
            inFlight.remove(requestId);
            output.onComplete(exchange);
        }
    }
}