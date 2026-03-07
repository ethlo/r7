package com.ethlo.venturi.api;

public final class UnproxiedUpstreamResponse implements GatewayResponse
{
    public static final UnproxiedUpstreamResponse INSTANCE = new UnproxiedUpstreamResponse();

    private UnproxiedUpstreamResponse()
    {
    }

    @Override
    public int status()
    {
        return 0;
    }

    @Override
    public GatewayHeaders headers()
    {
        return MutableGatewayHeaders.EMPTY;
    }
}