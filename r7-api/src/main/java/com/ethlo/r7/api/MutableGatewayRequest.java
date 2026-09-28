package com.ethlo.r7.api;

/**
 * A mutable view of a gateway request, primarily used for upstream URI and header manipulation.
 */
public interface MutableGatewayRequest extends GatewayRequest
{
    @Override
    MutableGatewayHeaders headers();

    /**
     * Updates the path component. Takes the <em>decoded</em> path, the same form
     * {@link #path()} returns: the gateway percent-encodes it for the wire, so a {@code ?},
     * {@code #}, {@code %} or space in it stays part of the path. Passing an already-encoded
     * path encodes it a second time.
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
     * Updates the full target URI. Sent to the upstream exactly as given, so it must already be
     * percent-encoded.
     */
    void uri(final String uri);

    /**
     * Overrides the HTTP method
     */
    void method(final String method);
}