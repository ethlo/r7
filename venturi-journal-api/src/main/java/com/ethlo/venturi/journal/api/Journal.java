package com.ethlo.venturi.journal.api;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.ServerDirection;

public interface Journal extends AutoCloseable
{
    /**
     * Records the metadata for a request or response.
     */
    void start(ServerDirection dir, JournalLevel journalLevel, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers) throws IOException;

    /**
     * Records a chunk of payload data.
     */
    void body(ServerDirection dir, CharSequence reqId, ByteBuffer data) throws IOException;

    /**
     * Records metrics and marks the exchange as finished.
     */
    void end(CharSequence reqId, GatewayAttributes attributes, int status, long sent, long recv, long duration) throws IOException;

    @Override
    void close() throws IOException;
}