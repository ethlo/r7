package com.ethlo.venturi.journal.api;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayHeaders;

public interface Journal extends AutoCloseable
{
    void clientRequest(JournalLevel level, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers);

    void upstreamRequest(JournalLevel level, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers);

    void upstreamResponse(JournalLevel level, CharSequence reqId, int status, ByteBuffer startLine, GatewayHeaders headers);

    void clientResponse(JournalLevel level, CharSequence reqId, int status, ByteBuffer startLine, GatewayHeaders headers);

    void requestBody(CharSequence reqId, ByteBuffer data);

    void responseBody(CharSequence reqId, ByteBuffer data);

    void endExchange(CharSequence reqId, GatewayAttributes attributes, int status, long sent, long recv, long duration, long reqCrc, long resCrc);

    @Override
    void close() throws IOException;
}