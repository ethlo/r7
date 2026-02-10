package com.ethlo.venturi.undertow;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.constants.HttpHeaders;
import com.ethlo.venturi.constants.HttpStatuses;
import com.ethlo.venturi.constants.MediaTypes;

public final class ShortCircuitFilter implements GatewayFilter
{
    private final ByteBuffer responseBytes = ByteBuffer.wrap("OK".getBytes(StandardCharsets.UTF_8));

    @Override
    public void beforeUpstream(GatewayExchange exchange)
    {
        exchange.response().setStatus(HttpStatuses.OK);
        exchange.response().headers().set(HttpHeaders.CONTENT_TYPE, MediaTypes.TEXT_PLAIN);
        exchange.response().localResponse(responseBytes);
    }
}