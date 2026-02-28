package com.ethlo.venturi.util;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.api.GatewayResponse;
import com.ethlo.venturi.api.MutableGatewayRequest;
import com.ethlo.venturi.api.MutableGatewayResponse;

public final class GatewayCopier
{
    private GatewayCopier()
    {
    }

    /**
     * Copies all headers from source to destination with zero-iterator-allocation.
     * * @param source The source headers
     *
     * @param destination The target mutable headers
     */
    public static void copy(GatewayHeaders source, MutableFastGatewayHeaders destination)
    {
        // We use the stateful forEach to avoid capturing lambdas and iterators
        source.forEach(destination, MutableFastGatewayHeaders::add);
    }

    /**
     * Creates a new MutableFastGatewayHeaders as a clone of the source.
     */
    public static MutableFastGatewayHeaders clone(GatewayHeaders source)
    {
        // Note: You could refine initial capacity if source provides a size() method
        final MutableFastGatewayHeaders destination = new MutableFastGatewayHeaders(16);
        copy(source, destination);
        return destination;
    }

    public static GatewayRequest clone(GatewayRequest originalRequest)
    {
        final MutableGatewayRequest r = new FastMutableGatewayRequest(clone(originalRequest.headers()));
        r.path(originalRequest.path());
        r.uri(originalRequest.uri());
        r.queryParams(originalRequest.queryParams());
        r.method(originalRequest.method());
        return r;
    }

    public static GatewayResponse clone(GatewayResponse originalResponse)
    {
        final MutableGatewayResponse r = new FastMutableGatewayResponse(clone(originalResponse.headers()));
        r.status(originalResponse.status());
        return r;
    }
}