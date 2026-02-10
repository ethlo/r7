package com.ethlo.venturi.core.filters;

import java.nio.ByteBuffer;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayResponse;
import com.ethlo.venturi.constants.HttpHeaders;
import com.ethlo.venturi.constants.HttpStatuses;
import com.ethlo.venturi.constants.MediaTypes;

public final class RequireAuthorizationHeaderFilter implements GatewayFilter
{
    @Override
    public void beforeUpstream(GatewayExchange exchange)
    {
        final String sig = (String) exchange.request().headers().getFirst(HttpHeaders.AUTHORIZATION);
        if (sig == null || !sig.startsWith("Bearer ") || !sig.startsWith("Basic "))
        {
            final GatewayResponse response = exchange.response();
            response.setStatus(HttpStatuses.UNAUTHORIZED);
            response.headers().set(HttpHeaders.CONTENT_TYPE, MediaTypes.TEXT_PLAIN);
            response.localResponse(ByteBuffer.wrap("Unauthorized".getBytes()));
        }
    }
}