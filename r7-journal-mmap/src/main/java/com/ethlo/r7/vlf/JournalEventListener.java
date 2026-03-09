package com.ethlo.r7.vlf;

import java.net.InetAddress;
import java.nio.ByteBuffer;

import com.ethlo.r7.api.GatewayAttributes;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.JournalLevel;

public interface JournalEventListener
{
    void onClientRequest(CharSequence reqId, JournalLevel level, CharSequence startLine, GatewayHeaders headers, InetAddress remoteAddress, IpSource ipSource);

    void onUpstreamRequest(CharSequence reqId, JournalLevel level, CharSequence startLine, GatewayHeaders headers);

    void onRequestBody(CharSequence reqId, ByteBuffer bodyChunk);

    void onResponseBody(CharSequence reqId, ByteBuffer bodyChunk);

    void onUpstreamResponse(CharSequence reqId, JournalLevel level, CharSequence startLine, GatewayHeaders headers);

    void onClientResponse(CharSequence reqId, JournalLevel level, CharSequence startLine, GatewayHeaders headers);

    void onEnd(CharSequence reqId, GatewayAttributes attributes,
               long clientStartTs, long clientEndTs,
               int status,
               long requestHeaderBytes, long requestBodyBytes, long responseHeaderBytes, long responseBodyBytes,
               long proxyStartTs, long proxyFirstByteReceivedTs, long proxyEndTs,
               final int requestCrc32, final int responseCrc32c);
}