package com.ethlo.venturi.undertow;

import java.io.OutputStream;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.GatewayRequest;
import io.undertow.server.HttpServerExchange;

public final class UndertowGatewayRequest implements GatewayRequest
{
    private final HttpServerExchange exchange;
    private final GatewayHeaders headers;

    public UndertowGatewayRequest(final HttpServerExchange exchange)
    {
        this.exchange = exchange;
        this.headers = new UndertowGatewayHeaders(exchange.getRequestHeaders());
    }

    @Override
    public CharSequence method()
    {
        return new HttpStringCharSequence(exchange.getRequestMethod());
    }

    @Override
    public CharSequence uri()
    {
        return exchange.getRequestURI();
    }

    @Override
    public CharSequence path()
    {
        return exchange.getRelativePath();
    }

    @Override
    public GatewayHeaders headers()
    {
        return headers;
    }

    @Override
    public void addStreamListener(final OutputStream out)
    {
        exchange.addRequestWrapper((factory, ex) -> new TeeingStreamSourceConduit(factory.create(), out));
    }
}