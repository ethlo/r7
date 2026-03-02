package com.ethlo.venturi.util;

import java.nio.ByteBuffer;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.TerminationGatewayResponse;
import com.ethlo.venturi.util.constants.HttpHeaders;

public class FastTerminationGatewayResponse implements TerminationGatewayResponse
{
    private final ByteBuffer body;
    private final GatewayHeaders headers;
    private final int status;

    public FastTerminationGatewayResponse(final int status, final String contentType, final ByteBuffer body)
    {
        this.body = body;
        this.headers = contentTypeHeaders(contentType);
        this.status = status;
    }

    public FastTerminationGatewayResponse(final GatewayHeaders headers, final int status, final ByteBuffer body)
    {
        this.body = body;
        this.headers = headers;
        this.status = status;
    }

    private static MutableFastGatewayHeaders contentTypeHeaders(String contentType)
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders(1);
        headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        return headers;
    }

    @Override
    public GatewayHeaders headers()
    {
        return headers;
    }

    @Override
    public boolean isCommitted()
    {
        return true;
    }

    @Override
    public int status()
    {
        return status;
    }

    @Override
    public ByteBuffer body()
    {
        return body;
    }
}
