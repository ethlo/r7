package com.ethlo.venturi.api;

/**
 * A representation of an HTTP response.
 */
public interface GatewayResponse
{
    /** @return the response headers */
    GatewayHeaders headers();

    /** @return the HTTP status code */
    int status();
}