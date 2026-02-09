package com.ethlo.venturi.undertow;

import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.api.GatewayResponse;

public final class RequireAuthorizationHeaderFilter implements GatewayFilter {
    @Override
    public void beforeUpstream(GatewayRequest req, GatewayResponse res, GatewayAttributes attrs) {
        final String sig = (String) req.headers().getFirst("Authorization");
        if (sig == null || !sig.startsWith("Bearer ")) {
            res.setStatus(401);
            res.localResponse("Unauthorized".getBytes(), "text/plain");
        }
    }
}