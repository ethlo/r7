package com.ethlo.venturi.api;

import java.io.OutputStream;

public interface GatewayRequest
{
    CharSequence method();

    CharSequence uri();

    CharSequence path();

    GatewayHeaders headers();

    /**
     * Registers a target that will receive a copy of all body bytes
     * as they are processed by the engine.
     */
    void addStreamListener(OutputStream out);
}