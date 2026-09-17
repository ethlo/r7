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

    /**
     * @param base the header set already journaled for this exchange's client request, against
     *             which this one may be recorded as a difference, or null when there is none to
     *             refer to. The gateway journals the request as received and as forwarded, and
     *             those differ only by what it did itself; passing the first lets the second be
     *             recorded as that difference rather than as a second full copy. An
     *             implementation may ignore it and write the whole set.
     */
    int upstreamRequest(JournalLevel level, String reqId, ByteBuffer startLine, GatewayHeaders headers, GatewayHeaders base);

    int upstreamResponse(JournalLevel level, String reqId, int status, ByteBuffer startLine, GatewayHeaders headers);

    /**
     * @param base as on {@link #upstreamRequest}, here the upstream response already journaled
     *             for this exchange
     */
    int clientResponse(JournalLevel level, String reqId, int status, ByteBuffer startLine, GatewayHeaders headers, GatewayHeaders base);

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