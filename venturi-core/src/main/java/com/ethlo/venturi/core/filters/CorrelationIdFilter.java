package com.ethlo.venturi.core.filters;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.constants.HttpHeaders;

public final class CorrelationIdFilter implements GatewayFilter
{
    @Override
    public void beforeUpstream(GatewayExchange exchange)
    {
        final CharSequence requestId = exchange.requestId();
        exchange.request().headers().addHeader(HttpHeaders.X_CORRELATION_ID, requestId);
        exchange.response().headers().addHeader(HttpHeaders.X_CORRELATION_ID, requestId);
    }
}