package com.ethlo.r7.journal.api;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import com.ethlo.r7.api.GatewayAttributes;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.IpSource;

public interface Journal extends AutoCloseable
{
    int clientRequest(JournalLevel level, String reqId, ByteBuffer startLine, GatewayHeaders headers, final InetAddress remoteAddress, final IpSource ipSource);

    int upstreamRequest(JournalLevel level, String reqId, ByteBuffer startLine, GatewayHeaders headers);

    int upstreamResponse(JournalLevel level, String reqId, int status, ByteBuffer startLine, GatewayHeaders headers);

    int clientResponse(JournalLevel level, String reqId, int status, ByteBuffer startLine, GatewayHeaders headers);

    int requestBody(String reqId, ByteBuffer data);

    int responseBody(String reqId, ByteBuffer data);

    /**
     * @param requestChecksum  checksum of the request body bytes this writer passed to the
     *                         journal, or {@link BodyChecksum#NOT_RECORDED} if it passed
     *                         none. These are typed rather than numeric because a checksum
     *                         cannot signal its own absence: while they were bare
     *                         {@code long}s, three call sites wrote a literal {@code 0} for
     *                         "none" and thereby claimed a 32&nbsp;KB body hashes to zero.
     * @param responseChecksum as above, for the response body
     */
    int endExchange(String reqId, GatewayAttributes attributes, final long requestStartTs, final long requestEndTs, int statusCode, long requestHeaderBytes, long requestBodyBytes, long responseHeaderBytes, long responseBodyBytes, final long proxyStartTs, final long proxyFirstByteReceivedTs, final long proxyEndTs, final BodyChecksum requestChecksum, final BodyChecksum responseChecksum);

    @Override
    void close() throws IOException;
}