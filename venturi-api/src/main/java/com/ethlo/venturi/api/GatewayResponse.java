package com.ethlo.venturi.api;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

public interface GatewayResponse
{
    GatewayHeaders headers();

    void status(int status);

    /**
     * Terminate the request here and return a fixed body.
     * This signals the engine to NOT call the upstream.
     */
    void commitResponse(ByteBuffer body);

    boolean isCommitted();

    int status();
}