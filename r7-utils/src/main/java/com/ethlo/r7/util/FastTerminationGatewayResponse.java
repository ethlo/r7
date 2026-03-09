package com.ethlo.r7.util;

import java.nio.ByteBuffer;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.ShortCircuitGatewayResponse;
import com.ethlo.r7.util.constants.HttpHeaders;

public class FastTerminationGatewayResponse implements ShortCircuitGatewayResponse
{
    private final ByteBuffer body;
    private final GatewayHeaders headers;
    private final int status;

    public FastTerminationGatewayResponse(final int status, final String contentType, final ByteBuffer body)
    {
        this(contentTypeHeaders(contentType), status, body);
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
