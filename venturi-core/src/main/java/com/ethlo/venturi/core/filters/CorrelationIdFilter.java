package com.ethlo.venturi.core.filters;

import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.api.GatewayResponse;

public final class CorrelationIdFilter implements GatewayFilter {
    @Override
    public void beforeUpstream(GatewayRequest req, GatewayResponse res, GatewayAttributes attrs) {
        res.headers().addHeader("X-Correlation-Id", attrs.get("request_id"));
    }
}