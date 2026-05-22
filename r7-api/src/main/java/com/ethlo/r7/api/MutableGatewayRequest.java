package com.ethlo.r7.api;

/**
 * A mutable view of a gateway request, primarily used for upstream URI and header manipulation.
 */
public interface MutableGatewayRequest extends GatewayRequest
{
    @Override
    MutableGatewayHeaders headers();

    /**
     * Updates the path component
     */
    void path(final String newPath);

    /**
     * Returns a mutable view of the query parameters
     */
    @Override
    MutableQueryParams queryParams();

    /**
     * Returns a mutable view of the cookies
     */
    @Override
    MutableCookies cookies();

    /**
     * Updates the full target URI
     */
    void uri(final String uri);

    /**
     * Overrides the HTTP method
     */
    void method(final String method);
}