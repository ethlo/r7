package com.ethlo.venturi.util;

import com.ethlo.venturi.api.MutableGatewayHeaders;
import com.ethlo.venturi.api.MutableGatewayRequest;

public class FastMutableGatewayRequest implements MutableGatewayRequest
{
    private final MutableGatewayHeaders headers;
    private CharSequence path;
    private CharSequence queryParams;
    private CharSequence uri;
    private CharSequence method;

    public FastMutableGatewayRequest(MutableGatewayHeaders headers)
    {
        this.headers = headers;
    }

    @Override
    public MutableGatewayHeaders headers()
    {
        return headers;
    }

    @Override
    public void path(CharSequence newPath)
    {
        this.path = newPath;
    }

    @Override
    public void queryParams(CharSequence newQueryParams)
    {
        this.queryParams = newQueryParams;
    }

    @Override
    public void uri(CharSequence uri)
    {
        this.uri = uri;
    }

    @Override
    public void method(final CharSequence method)
    {
        this.method = method;
    }

    @Override
    public CharSequence method()
    {
        return method;
    }

    @Override
    public CharSequence uri()
    {
        return uri;
    }

    @Override
    public CharSequence path()
    {
        return path;
    }

    @Override
    public CharSequence queryParams()
    {
        return queryParams;
    }
}