package com.ethlo.venturi.api;

import java.nio.ByteBuffer;

public interface ShortCircuitGatewayResponse extends GatewayResponse
{
    GatewayHeaders headers();

    int status();

    ByteBuffer body();
}