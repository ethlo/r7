package com.ethlo.venturi.undertow;

import com.ethlo.venturi.api.MutableGatewayHeaders;
import com.ethlo.venturi.api.MutableGatewayResponse;
import io.undertow.server.HttpServerExchange;

public final class UndertowGatewayResponse implements MutableGatewayResponse
{
    private final HttpServerExchange exchange;
    private final MutableGatewayHeaders headers;
    private boolean isCommitted;

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
    public void clientResponseComitted()
    {
        this.isCommitted = true;
    }

    @Override
    public boolean isCommitted()
    {
        return isCommitted;
    }

    @Override
    public int status()
    {
        return exchange.getStatusCode();
    }
}