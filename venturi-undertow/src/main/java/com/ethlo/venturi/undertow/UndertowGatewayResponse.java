package com.ethlo.venturi.undertow;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.GatewayResponse;
import io.undertow.server.HttpServerExchange;

public final class UndertowGatewayResponse implements GatewayResponse
{
    private final HttpServerExchange exchange;
    private final GatewayHeaders headers;
    private boolean isCommitted;

    public UndertowGatewayResponse(final HttpServerExchange exchange)
    {
        this.exchange = exchange;
        this.headers = new UndertowGatewayHeaders(exchange.getResponseHeaders());
    }

    @Override
    public GatewayHeaders headers()
    {
        return headers;
    }

    @Override
    public void status(final int status)
    {
        exchange.setStatusCode(status);
    }

    @Override
    public void addBodyListener(final Consumer<ByteBuffer> listener)
    {
        exchange.addResponseWrapper((factory, ex) ->
                new TeeingStreamSinkConduit(factory.create(), listener));
    }

    @Override
    public void commitResponse(final ByteBuffer body)
    {
        exchange.getResponseSender().send(body);
        isCommitted = true;
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