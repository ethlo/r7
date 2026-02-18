package com.ethlo.venturi.api;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

public interface GatewayRequest
{
    CharSequence method();

    CharSequence uri();

    CharSequence path();

    CharSequence queryParams();

    GatewayHeaders headers();

    /**
     * Receives the raw ByteBuffers as they flow through the engine.
     * The engine guarantees the buffer is in 'read mode', i.e. ready for a writ operation.
     */
    void addBodyListener(Consumer<ByteBuffer> listener);

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