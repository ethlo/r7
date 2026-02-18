package com.ethlo.venturi.undertow;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

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
        return exchange.getRequestPath();
    }

    @Override
    public CharSequence queryParams()
    {
        return exchange.getDecodedQueryString();
    }

    @Override
    public GatewayHeaders headers()
    {
        return headers;
    }

    @Override
    public void addBodyListener(final Consumer<ByteBuffer> listener)
    {
        exchange.addRequestWrapper((factory, ex) -> new TeeingStreamSourceConduit(factory.create(), listener));
    }

    @Override
    public void path(final CharSequence path)
    {
        final String newPath = path.toString();
        this.exchange.setRequestPath(newPath);     // The general path
        this.exchange.setRelativePath(newPath);    // Used by ProxyHandler to build upstream URL
        this.exchange.setRequestURI(newPath);      // The full URI used for logging/matching
    }

    @Override
    public void uri(final CharSequence uri)
    {
        this.exchange.setRequestURI(uri.toString());
    }

    public HttpServerExchange getExchange()
    {
        return exchange;
    }
}