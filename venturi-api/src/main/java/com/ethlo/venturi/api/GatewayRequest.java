package com.ethlo.venturi.api;

public interface GatewayRequest
{
    CharSequence method();

    CharSequence uri();

    CharSequence path();

    CharSequence queryParams();

    GatewayHeaders headers();

    /**
     * Sets the path for the upstream request
     *
     * @param path the path used by the upstream request
     */
    void path(CharSequence path);

    /**
     * Sets the URI for the upstream request
     *
     * @param uri the URI used by the upstream request
     */
    void uri(CharSequence uri);
}