package com.ethlo.venturi.core.filters;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.constants.HttpHeaders;
import com.ethlo.venturi.constants.HttpStatuses;

public final class RequireAuthorizationHeaderFilter implements GatewayFilter
{
    @Override
    public void beforeUpstream(GatewayExchange exchange)
    {
        final String sig = (String) exchange.request().headers().getFirst(HttpHeaders.AUTHORIZATION);
        if (sig == null || !sig.startsWith("Bearer ") || !sig.startsWith("Basic "))
        {
            exchange.response().setStatus(HttpStatuses.UNAUTHORIZED);
            exchange.response().localResponse("Unauthorized".getBytes(), "text/plain");
        }
    }
}