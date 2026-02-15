package com.ethlo.venturi.core.storage.mmap;

import com.ethlo.venturi.core.ServerDirection;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class ExchangeReassembler implements JournalEventListener
{
    private final Map<String, JournalExchange> inFlight = new HashMap<>();
    private final ExchangeCompletionListener output;

    public ExchangeReassembler(ExchangeCompletionListener output)
    {
        this.output = output;
    }

    @Override
    public void onBegin(ServerDirection dir, String id, String line, Map<String, String> headers)
    {
        JournalExchange exchange = inFlight.computeIfAbsent(id, k -> new JournalExchange(id));
        if (dir == ServerDirection.REQUEST)
        {
            exchange.setRequest(line, headers);
        }
        else
        {
            exchange.setResponse(line, headers);
        }
    }

    @Override
    public void onBody(ServerDirection dir, String id, ByteBuffer body)
    {
        JournalExchange exchange = inFlight.get(id);
        if (exchange != null)
        {
            // zero-copy append
            exchange.appendBody(dir, body);
        }
    }

    @Override
    public void onEnd(String id, long ts, int status, long sent, long recv, long dur)
    {
        JournalExchange exchange = inFlight.remove(id);
        if (exchange != null)
        {
            exchange.setMetrics(ts, status, sent, recv, dur);
            output.onComplete(exchange); // Notify Jackson Writer here
        }
    }
}