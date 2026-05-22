package com.ethlo.r7.r7f;

import java.net.InetAddress;
import java.nio.ByteBuffer;

import com.ethlo.r7.api.GatewayAttributes;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.JournalLevel;

public interface JournalEventListener
{
    void onClientRequest(String reqId, JournalLevel level, String startLine, GatewayHeaders headers, InetAddress remoteAddress, IpSource ipSource);

    void onUpstreamRequest(String reqId, JournalLevel level, String startLine, GatewayHeaders headers);

    void onRequestBody(String reqId, ByteBuffer bodyChunk);

    void onResponseBody(String reqId, ByteBuffer bodyChunk);

    void onUpstreamResponse(String reqId, JournalLevel level, String startLine, GatewayHeaders headers);

    void onClientResponse(String reqId, JournalLevel level, String startLine, GatewayHeaders headers);

    void onEnd(String reqId, GatewayAttributes attributes,
               long clientStartTs, long clientEndTs,
               int status,
               long requestHeaderBytes, long requestBodyBytes, long responseHeaderBytes, long responseBodyBytes,
               long proxyStartTs, long proxyFirstByteReceivedTs, long proxyEndTs,
               final int requestCrc32, final int responseCrc32c);
}