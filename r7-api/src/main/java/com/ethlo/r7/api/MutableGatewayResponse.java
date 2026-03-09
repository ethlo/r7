package com.ethlo.r7.api;

/**
 * A mutable view of the response being prepared for the client.
 */
public interface MutableGatewayResponse extends GatewayResponse
{
    @Override
    MutableGatewayHeaders headers();

    /**
     * Sets the HTTP status code
     */
    void status(int status);
}