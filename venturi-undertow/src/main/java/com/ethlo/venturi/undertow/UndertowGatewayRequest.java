package com.ethlo.venturi.undertow;

import com.ethlo.venturi.api.MutableGatewayHeaders;
import com.ethlo.venturi.api.MutableGatewayRequest;
import com.ethlo.venturi.undertow.util.HttpStringUtil;
import com.ethlo.venturi.util.HttpStringCharSequence;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;

public final class UndertowGatewayRequest implements MutableGatewayRequest
{
    private final HttpServerExchange exchange;
    private final MutableGatewayHeaders headers;

    public UndertowGatewayRequest(final HttpServerExchange exchange)
    {
        this.exchange = exchange;
        this.headers = new UndertowGatewayHeaders(exchange.getRequestHeaders());
    }

    @Override
    public CharSequence method()
    {
        final HttpString hs = exchange.getRequestMethod();
        return new HttpStringCharSequence(hs, hs.hashCode(), HttpStringUtil.getBytes(hs));
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
    public MutableGatewayHeaders headers()
    {
        return headers;
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
    public void queryParams(final CharSequence newQueryParams)
    {
        this.exchange.setQueryString(newQueryParams.toString());
    }

    @Override
    public void uri(final CharSequence uri)
    {
        this.exchange.setRequestURI(uri.toString());
    }

    @Override
    public void method(final CharSequence method)
    {
        this.exchange.setRequestMethod(HttpString.tryFromString(method.toString()));
    }

    public HttpServerExchange getExchange()
    {
        return exchange;
    }
}