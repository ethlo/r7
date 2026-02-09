package com.ethlo.venturi.undertow;

import java.nio.charset.StandardCharsets;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.constants.HttpStatuses;
import com.ethlo.venturi.constants.MediaTypes;

public final class ShortCircuitFilter implements GatewayFilter
{
    private final byte[] responseBytes = "OK".getBytes(StandardCharsets.UTF_8);

    @Override
    public void beforeUpstream(GatewayExchange exchange)
    {
        exchange.response().setStatus(HttpStatuses.OK);
        exchange.response().localResponse(responseBytes, MediaTypes.TEXT_PLAIN);
    }
}