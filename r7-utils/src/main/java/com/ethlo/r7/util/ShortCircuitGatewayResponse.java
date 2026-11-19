package com.ethlo.r7.util;

import java.nio.ByteBuffer;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.util.constants.HttpHeaders;

public class ShortCircuitGatewayResponse implements com.ethlo.r7.api.ShortCircuitGatewayResponse
{
    private final ByteBuffer body;
    private final GatewayHeaders headers;
    private final int status;

    public ShortCircuitGatewayResponse(final int status, final String contentType, final ByteBuffer body)
    {
        this(contentTypeHeaders(contentType), status, body);
    }

    public ShortCircuitGatewayResponse(final GatewayHeaders headers, final int status, final ByteBuffer body)
    {
        this.body = body;
        this.headers = headers;
        this.status = status;
    }

    /**
     * Builds the header set for a short-circuit response.
     * <p>
     * A {@code null} content type means the response does not declare one — for example a
     * static-content short circuit, where the type is decided later from the file being
     * served. That must produce <em>no</em> Content-Type header, not a header whose value
     * is null: a null value has no valid representation on the wire and none in the
     * journal either.
     */
    private static MutableFastGatewayHeaders contentTypeHeaders(String contentType)
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders(1);
        if (contentType != null)
        {
            headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        }
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
