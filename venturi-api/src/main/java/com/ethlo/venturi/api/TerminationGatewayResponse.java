package com.ethlo.venturi.api;

import java.nio.ByteBuffer;

public interface TerminationGatewayResponse extends GatewayResponse
{
    GatewayHeaders headers();

    int status();

    ByteBuffer body();
}