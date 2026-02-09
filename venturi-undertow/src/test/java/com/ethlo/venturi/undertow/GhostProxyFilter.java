package com.ethlo.venturi.undertow;

import java.nio.charset.StandardCharsets;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.constants.HttpStatuses;
import com.ethlo.venturi.constants.MediaTypes;

public final class GhostProxyFilter implements GatewayFilter
{
    private final byte[] mockPayload = "{\"status\": \"proxied\"}".getBytes(StandardCharsets.UTF_8);

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
        exchange.response().setStatus(HttpStatuses.OK);
        exchange.response().localResponse(mockPayload, MediaTypes.APPLICATION_JSON);
    }
}