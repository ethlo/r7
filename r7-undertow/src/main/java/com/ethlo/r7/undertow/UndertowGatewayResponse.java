package com.ethlo.r7.undertow;

import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.MutableGatewayResponse;
import io.undertow.server.HttpServerExchange;

public final class UndertowGatewayResponse implements MutableGatewayResponse
{
    private final HttpServerExchange exchange;
    private final MutableGatewayHeaders headers;

    public UndertowGatewayResponse(final HttpServerExchange exchange)
    {
        this.exchange = exchange;
        this.headers = new UndertowGatewayHeaders(exchange.getResponseHeaders());
    }

    @Override
    public MutableGatewayHeaders headers()
    {
        return headers;
    }

    @Override
    public void status(final int status)
    {
        this.exchange.setStatusCode(status);
    }

    @Override
    public int status()
    {
        return exchange.getStatusCode();
    }
}