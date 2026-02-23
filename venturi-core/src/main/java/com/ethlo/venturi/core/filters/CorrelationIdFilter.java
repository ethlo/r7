package com.ethlo.venturi.core.filters;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.core.ShortInfo;

import static com.ethlo.venturi.util.constants.HttpHeaders.X_CORRELATION_ID;

public final class CorrelationIdFilter implements GatewayFilter, ShortInfo
{
    @Override
    public void beforeUpstream(GatewayExchange exchange)
    {
        final CharSequence requestId = exchange.requestId();
        exchange.request().headers().add(X_CORRELATION_ID, requestId);
        exchange.response().headers().add(X_CORRELATION_ID, requestId);
    }

    @Override
    public String summary()
    {
        return "CorrelationIdFilter: " + X_CORRELATION_ID;
    }
}