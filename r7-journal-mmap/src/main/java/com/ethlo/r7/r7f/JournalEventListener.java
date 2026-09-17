package com.ethlo.r7.r7f;

import java.net.InetAddress;
import java.nio.ByteBuffer;

import com.ethlo.r7.api.GatewayAttributes;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.r7f.fbs.HeaderDelta;

public interface JournalEventListener
{
    void onClientRequest(String reqId, JournalLevel level, String startLine, GatewayHeaders headers, InetAddress remoteAddress, IpSource ipSource);

    /**
     * @param headers the full set, or null when {@code delta} carries it instead
     * @param delta   the set expressed as a difference from the client request, or null when
     *                {@code headers} carries it. Exactly one of the two is present. The delta is
     *                a view over the reader's buffer and is only valid for the duration of this
     *                call, so a listener that keeps anything must resolve it now.
     */
    void onUpstreamRequest(String reqId, JournalLevel level, String startLine, GatewayHeaders headers, HeaderDelta delta);

    void onRequestBody(String reqId, ByteBuffer bodyChunk);

    void onResponseBody(String reqId, ByteBuffer bodyChunk);

    void onUpstreamResponse(String reqId, JournalLevel level, String startLine, GatewayHeaders headers);

    /**
     * @param delta as on {@link #onUpstreamRequest}, here a difference from the upstream response
     */
    void onClientResponse(String reqId, JournalLevel level, String startLine, GatewayHeaders headers, HeaderDelta delta);

    void onEnd(String reqId, GatewayAttributes attributes,
               long clientStartTs, long clientEndTs,
               int status,
               long requestHeaderBytes, long requestBodyBytes, long responseHeaderBytes, long responseBodyBytes,
               long proxyStartTs, long proxyFirstByteReceivedTs, long proxyEndTs,
               final BodyChecksum requestChecksum, final BodyChecksum responseChecksum);
}