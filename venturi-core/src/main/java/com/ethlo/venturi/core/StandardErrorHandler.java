package com.ethlo.venturi.core;

import com.ethlo.venturi.api.*;
import java.time.Instant;

public final class StandardErrorHandler implements GatewayErrorHandler {
    @Override
    public void handleError(final GatewayRequest req, final GatewayResponse res, final GatewayAttributes attrs, final Throwable error) {
        final CharSequence id = attrs.get("request_id");
        final String message = error != null ? error.getMessage() : "Upstream connection failed";

        // 1. Standardized high-visibility logging
        System.err.printf("[%s] [GATEWAY-ERROR] ID: %s | URI: %s | MSG: %s%n", 
            Instant.now(), id, req.uri(), message);
        
        if (error != null) error.printStackTrace(System.err);

        // 2. Standardized JSON error response
        final String jsonError = String.format("{\"id\":\"%s\",\"error\":\"%s\"}", id, message);
        res.setStatus(503); // Service Unavailable
        res.localResponse(jsonError.getBytes(), "application/json");
    }
}