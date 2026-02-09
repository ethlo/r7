package com.ethlo.venturi.undertow;

import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.api.GatewayResponse;

import java.nio.charset.StandardCharsets;

public final class GhostProxyFilter implements GatewayFilter {
    private final byte[] mockPayload = "{\"status\": \"proxied\"}".getBytes(StandardCharsets.UTF_8);

    @Override
    public void beforeUpstream(GatewayRequest req, GatewayResponse res, GatewayAttributes attrs) {
        // 1. Simulate Network Latency (e.g., 1ms round trip)
        // On Virtual Threads, this parks the thread but releases the carrier.
        try {
            Thread.sleep(1); 
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 2. Return a local response AFTER the "wait"
        res.setStatus(200);
        res.localResponse(mockPayload, "application/json");
    }
}