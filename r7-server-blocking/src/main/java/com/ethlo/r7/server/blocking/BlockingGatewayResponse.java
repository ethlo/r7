package com.ethlo.r7.server.blocking;

import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.MutableGatewayResponse;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * The client response while the pipeline and filters shape it. Nothing reaches the server until
 * the exchange commits, which is what lets response filters change the upstream's status and
 * headers.
 */
public final class BlockingGatewayResponse implements MutableGatewayResponse
{
    private final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();
    private int status = 200;

    @Override
    public MutableGatewayHeaders headers()
    {
        return this.headers;
    }

    @Override
    public void status(final int status)
    {
        this.status = status;
    }

    @Override
    public int status()
    {
        return this.status;
    }
}
