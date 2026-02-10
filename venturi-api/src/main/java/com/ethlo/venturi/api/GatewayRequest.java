package com.ethlo.venturi.api;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

public interface GatewayRequest
{
    CharSequence method();

    CharSequence uri();

    CharSequence path();

    GatewayHeaders headers();

    /**
     * Receives the raw ByteBuffers as they flow through the engine.
     * The engine guarantees the buffer is in 'read mode', i.e. ready for a writ operation.
     */
    void addBodyListener(Consumer<ByteBuffer> listener);
}