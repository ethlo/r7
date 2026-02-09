package com.ethlo.venturi.core.filters;

import com.ethlo.venturi.api.*;

public final class RequireAuthorizationHeaderFilter implements GatewayFilter {
    @Override
    public void beforeUpstream(GatewayRequest req, GatewayResponse res, GatewayAttributes attrs) {
        final String sig = (String) req.headers().getFirst(HttpHeaders.AUTHORIZATION);
        if (sig == null || !sig.startsWith("Bearer ")) {
            res.setStatus(HttpStatuses.UNAUTHORIZED);
            res.localResponse("Unauthorized".getBytes(), "text/plain");
        }
    }
}