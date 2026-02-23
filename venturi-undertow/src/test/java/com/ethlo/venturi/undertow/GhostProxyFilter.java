package com.ethlo.venturi.undertow;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.util.constants.HttpHeaders;
import com.ethlo.venturi.util.constants.HttpStatuses;
import com.ethlo.venturi.util.constants.MediaTypes;

public final class GhostProxyFilter implements GatewayFilter
{
    private final ByteBuffer mockPayload = ByteBuffer.wrap("{\"status\": \"proxied\"}".getBytes(StandardCharsets.UTF_8));

    @Override
    public void beforeUpstream(GatewayExchange exchange)
    {
        // 1. Simulate Network Latency (e.g., 1ms round trip)
        // On Virtual Threads, this parks the thread but releases the carrier.
        try
        {
            Thread.sleep(1);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }

        // 2. Return a local response AFTER the "wait"
        exchange.response().status(HttpStatuses.OK);
        exchange.request().headers().set(HttpHeaders.CONTENT_TYPE, MediaTypes.APPLICATION_JSON);
        exchange.response().commitResponse(mockPayload);
    }
}