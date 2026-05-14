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
    void path(CharSequence newPath);

    /**
     * Updates the raw query string
     */
    void queryParams(CharSequence newQueryParams);

    /**
     * Updates the full target URI
     */
    void uri(CharSequence uri);

    /**
     * Overrides the HTTP method
     */
    void method(CharSequence method);
}