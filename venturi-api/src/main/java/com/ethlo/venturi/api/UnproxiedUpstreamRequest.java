package com.ethlo.venturi.api;

import java.net.InetAddress;

public final class UnproxiedUpstreamRequest implements MutableGatewayRequest
{
    public static final UnproxiedUpstreamRequest INSTANCE = new UnproxiedUpstreamRequest();

    private UnproxiedUpstreamRequest() 
    {
    }

    @Override
    public String method()
    {
        return "";
    }

    @Override
    public String uri()
    {
        return "";
    }

    @Override
    public CharSequence path()
    {
        return "";
    }

    @Override
    public CharSequence queryParams()
    {
        return "";
    }

    @Override
    public MutableGatewayHeaders headers()
    {
        return MutableGatewayHeaders.EMPTY;
    }

    @Override
    public void path(final CharSequence newPath)
    {

    }

    @Override
    public void queryParams(final CharSequence newQueryParams)
    {

    }

    @Override
    public void uri(final CharSequence uri)
    {

    }

    @Override
    public void method(final CharSequence method)
    {

    }

    @Override
    public InetAddress remoteAddress()
    {
        return null;
    }
}