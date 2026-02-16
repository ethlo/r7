package com.ethlo.venturi.auditing.api;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.ServerDirection;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface Journal extends AutoCloseable
{
    /**
     * Records the metadata for a request or response.
     */
    void start(ServerDirection dir, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers) throws IOException;

    /**
     * Records a chunk of payload data.
     */
    void body(ServerDirection dir, CharSequence reqId, ByteBuffer data) throws IOException;

    /**
     * Records metrics and marks the exchange as finished.
     */
    void end(CharSequence reqId, int status, long sent, long recv, long duration);

    @Override
    void close() throws IOException;
}