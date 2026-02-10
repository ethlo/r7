package com.ethlo.venturi.api;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

public interface GatewayResponse
{

    GatewayHeaders headers();

    void setStatus(int status);

    /**
     * Receives the raw ByteBuffers as they flow through the engine.
     * The engine guarantees the buffer is in 'read mode', i.e. ready for a writ operation.
     */
    void addBodyListener(Consumer<ByteBuffer> listener);

    /**
     * Terminate the request here and return a fixed body.
     * This signals the engine to NOT call the upstream.
     */
    void localResponse(ByteBuffer body);

    boolean isCommitted();

    int status();
}