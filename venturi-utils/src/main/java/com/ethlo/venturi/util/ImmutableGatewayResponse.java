package com.ethlo.venturi.util;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.GatewayResponse;

public class ImmutableGatewayResponse implements GatewayResponse
{
    private final GatewayHeaders headers;
    private final int status;
    private final boolean commited;

    public ImmutableGatewayResponse(final GatewayHeaders headers, final int status, final boolean commited)
    {
        this.headers = headers;
        this.status = status;
        this.commited = commited;
    }

    @Override
    public GatewayHeaders headers()
    {
        return headers;
    }

    @Override
    public boolean isCommitted()
    {
        return this.commited;
    }

    @Override
    public int status()
    {
        return status;
    }
}