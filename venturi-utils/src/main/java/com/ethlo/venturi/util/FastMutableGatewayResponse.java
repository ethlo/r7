package com.ethlo.venturi.util;

import java.nio.ByteBuffer;

import com.ethlo.venturi.api.MutableGatewayHeaders;
import com.ethlo.venturi.api.MutableGatewayResponse;

public class FastMutableGatewayResponse implements MutableGatewayResponse
{
    private final MutableGatewayHeaders headers;
    private int status;
    private boolean aBoolean;
    private boolean commited;
    private ByteBuffer body;

    public FastMutableGatewayResponse(final MutableGatewayHeaders headers)
    {
        this.headers = headers;
    }

    @Override
    public MutableGatewayHeaders headers()
    {
        return headers;
    }

    @Override
    public void status(final int status)
    {
        this.status = status;
    }

    @Override
    public void commitResponse(final ByteBuffer body)
    {
        this.body = body;
        this.commited = true;
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