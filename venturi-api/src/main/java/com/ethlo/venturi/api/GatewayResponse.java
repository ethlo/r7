package com.ethlo.venturi.api;

import java.io.OutputStream;

public interface GatewayResponse {

    GatewayHeaders headers();

    void setStatus(int status);

    /**
     * Registers a target to receive a copy of the response body
     * as it is streamed back from the upstream server.
     */
    void addStreamListener(OutputStream out);

    /**
     * Terminate the request here and return a fixed body.
     * This signals the engine to NOT call the upstream.
     */
    void localResponse(byte[] body, CharSequence contentType);

    boolean isCommitted();
}