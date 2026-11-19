package com.ethlo.r7.api;

import java.nio.ByteBuffer;

/**
 * Defines a complete response generated locally by the gateway for an early exit.
 */
public interface ShortCircuitGatewayResponse extends GatewayResponse
{
    /**
     * @return the static body payload for the response
     */
    ByteBuffer body();
}