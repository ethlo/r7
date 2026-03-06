package com.ethlo.venturi.journal.api;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.IpSource;

public interface Journal extends AutoCloseable
{
    int clientRequest(JournalLevel level, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers, final InetAddress remoteAddress, final IpSource ipSource);

    int upstreamRequest(JournalLevel level, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers);

    int upstreamResponse(JournalLevel level, CharSequence reqId, int status, ByteBuffer startLine, GatewayHeaders headers);

    int clientResponse(JournalLevel level, CharSequence reqId, int status, ByteBuffer startLine, GatewayHeaders headers);

    int requestBody(CharSequence reqId, ByteBuffer data);

    int responseBody(CharSequence reqId, ByteBuffer data);

    int endExchange(CharSequence reqId, GatewayAttributes attributes, final long requestStartTs, final long requestEndTs, int statusCode, long requestHeaderBytes, long requestBodyBytes, long responseHeaderBytes, long responseBodyBytes, final long proxyStartTs, final long proxyFirstByteReceivedTs, final long proxyEndTs, final int requestChecksumValue, final int responseChecksumValue);

    @Override
    void close() throws IOException;
}