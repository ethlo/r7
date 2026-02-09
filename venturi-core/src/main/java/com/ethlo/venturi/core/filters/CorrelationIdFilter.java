package com.ethlo.venturi.core.filters;

import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.api.GatewayResponse;

import java.nio.charset.StandardCharsets;

public final class ShortCircuitFilter implements GatewayFilter {
    private final byte[] responseBytes = "OK".getBytes(StandardCharsets.UTF_8);

    @Override
    public void beforeUpstream(GatewayRequest req, GatewayResponse res, GatewayAttributes attrs) {
        res.setStatus(200);
        res.headers().addHeader("X-Correlation-Id", attrs.get("request_id"));
        res.localResponse(responseBytes, "text/plain");
    }
}