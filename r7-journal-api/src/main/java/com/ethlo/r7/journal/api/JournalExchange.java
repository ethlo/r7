package com.ethlo.r7.journal.api;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

import com.ethlo.r7.api.GatewayAttributes;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.IpSource;

/**
 * Stateful container for a single request/response lifecycle.
 * <p>
 * Body fragments are copied out of the reader's buffers on arrival — see
 * {@link #retain(ByteBuffer)} for why the zero-copy slice cannot be kept.
 */
public final class JournalExchange
{
    private final String requestId;
    private final List<ByteBuffer> requestBodyFragments = new ArrayList<>(0);
    private final List<ByteBuffer> responseBodyFragments = new ArrayList<>(0);

    // --- Slice 1: Pristine Ingress ---
    private String clientRequestStartLine;
    private GatewayHeaders clientRequestHeaders;
    private JournalLevel clientRequestLevel;

    // --- Slice 2: Upstream Intent ---
    private String upstreamRequestStartLine;
    private GatewayHeaders upstreamRequestHeaders;
    private JournalLevel upstreamRequestLevel;

    // --- Slice 3: Raw Backend Return ---
    private String upstreamResponseStartLine;
    private GatewayHeaders upstreamResponseHeaders;
    private JournalLevel upstreamResponseLevel;

    // --- Slice 4: Final Client Egress ---
    private String clientResponseStartLine;
    private GatewayHeaders clientResponseHeaders;
    private JournalLevel clientResponseLevel;

    // Metrics & Forensic Metadata
    private int status;
    private GatewayAttributes attributes;
    private BodyChecksum journaledRequestChecksum = BodyChecksum.NOT_RECORDED;
    private BodyChecksum journaledResponseChecksum = BodyChecksum.NOT_RECORDED;
    private long clientStartTs;
    private long clientEndTs;
    private long proxyStartTs;
    private long proxyFirstByteReceivedTs;
    private long proxyEndTs;
    private long requestHeaderBytes;
    private long requestBodyBytes;
    private long responseHeaderBytes;
    private long responseBodyBytes;
    private InetAddress remoteAddress;
    private IpSource remoteAddressSource;

    /**
     * Monotonic creation stamp, used to age out exchanges whose EndExchange never
     * arrived. {@link System#nanoTime()} rather than wall-clock, so that an NTP step
     * cannot make an entry look arbitrarily old or young.
     */
    private final long createdAtNanos = System.nanoTime();

    // Checksums computed over the body fragments actually observed, per exchange, for
    // comparison against the values the gateway recorded in the EndExchange event.
    private CRC32C observedRequestCrc;
    private CRC32C observedResponseCrc;


    public JournalExchange(String requestId)
    {
        this.requestId = requestId;
    }

    public long getCreatedAtNanos()
    {
        return createdAtNanos;
    }

    public void setClientRequest(String line, JournalLevel level, GatewayHeaders headers, InetAddress remoteAddress, final IpSource ipSource)
    {
        this.clientRequestStartLine = line;
        this.clientRequestLevel = level;
        this.clientRequestHeaders = headers;
        this.remoteAddress = remoteAddress;
        this.remoteAddressSource = ipSource;
    }

    public void setUpstreamRequest(String line, JournalLevel level, GatewayHeaders headers)
    {
        this.upstreamRequestStartLine = line;
        this.upstreamRequestLevel = level;
        this.upstreamRequestHeaders = headers;
    }

    public void setUpstreamResponse(String line, JournalLevel level, GatewayHeaders headers)
    {
        this.upstreamResponseStartLine = line;
        this.upstreamResponseLevel = level;
        this.upstreamResponseHeaders = headers;
    }

    public void setClientResponse(String line, JournalLevel level, GatewayHeaders headers)
    {
        this.clientResponseStartLine = line;
        this.clientResponseLevel = level;
        this.clientResponseHeaders = headers;
    }

    public void appendRequestBody(ByteBuffer fragment)
    {
        if (observedRequestCrc == null)
        {
            observedRequestCrc = new CRC32C();
        }
        observedRequestCrc.update(fragment.duplicate());
        requestBodyFragments.add(retain(fragment));
    }

    public void appendResponseBody(ByteBuffer fragment)
    {
        if (observedResponseCrc == null)
        {
            observedResponseCrc = new CRC32C();
        }
        observedResponseCrc.update(fragment.duplicate());
        responseBodyFragments.add(retain(fragment));
    }

    /**
     * Copies a body fragment out of the reader's buffer.
     * <p>
     * Fragments arrive as slices of whatever the reader is currently looking at: a mapped
     * segment, or a reusable decompression buffer. An exchange outlives that — it is held
     * until its EndExchange arrives, which can be in a later segment entirely, by which
     * point the mapping is closed and the decompression buffer has been overwritten by the
     * next file. Keeping the slice would mean the body silently changes contents under the
     * consumer, which is precisely the failure an audit log cannot have.
     * <p>
     * This is the one place the reader deliberately allocates. It is on the reader side,
     * not the gateway write path, so it costs nothing in the hot path.
     */
    private static ByteBuffer retain(final ByteBuffer fragment)
    {
        final ByteBuffer copy = ByteBuffer.allocate(fragment.remaining());
        copy.put(fragment.duplicate());
        return copy.flip();
    }

    /**
     * CRC32C of the request body fragments seen by this reader, or
     * {@link BodyChecksum#NOT_RECORDED} if no request body was read back.
     */
    public BodyChecksum getObservedRequestChecksum()
    {
        return BodyChecksum.of(observedRequestCrc);
    }

    /**
     * CRC32C of the response body fragments seen by this reader, or
     * {@link BodyChecksum#NOT_RECORDED} if no response body was read back.
     */
    public BodyChecksum getObservedResponseChecksum()
    {
        return BodyChecksum.of(observedResponseCrc);
    }

    public void setTiming(final long clientStartTs, final long clientEndTs, final long proxyStartTs, final long proxyFirstByteReceivedTs, final long proxyEndTs)
    {
        this.clientStartTs = clientStartTs;
        this.clientEndTs = clientEndTs;
        this.proxyStartTs = proxyStartTs;
        this.proxyFirstByteReceivedTs = proxyFirstByteReceivedTs;
        this.proxyEndTs = proxyEndTs;
    }

    public void setJournalChecksums(BodyChecksum requestChecksum, BodyChecksum responseChecksum)
    {
        this.journaledRequestChecksum = requestChecksum;
        this.journaledResponseChecksum = responseChecksum;
    }

    public String getRequestId()
    {
        return requestId;
    }

    public String getClientRequestStartLine()
    {
        return clientRequestStartLine;
    }

    public GatewayHeaders getClientRequestHeaders()
    {
        return clientRequestHeaders;
    }

    public JournalLevel getClientRequestLevel()
    {
        return clientRequestLevel;
    }

    public String getUpstreamRequestStartLine()
    {
        return upstreamRequestStartLine;
    }

    public GatewayHeaders getUpstreamRequestHeaders()
    {
        return upstreamRequestHeaders;
    }

    public JournalLevel getUpstreamRequestLevel()
    {
        return upstreamRequestLevel;
    }

    public String getUpstreamResponseStartLine()
    {
        return upstreamResponseStartLine;
    }

    public GatewayHeaders getUpstreamResponseHeaders()
    {
        return upstreamResponseHeaders;
    }

    public JournalLevel getUpstreamResponseLevel()
    {
        return upstreamResponseLevel;
    }

    public String getClientResponseStartLine()
    {
        return clientResponseStartLine;
    }

    public GatewayHeaders getClientResponseHeaders()
    {
        return clientResponseHeaders;
    }

    public JournalLevel getClientResponseLevel()
    {
        return clientResponseLevel;
    }

    public List<ByteBuffer> getRequestBodyFragments()
    {
        return requestBodyFragments;
    }

    public List<ByteBuffer> getResponseBodyFragments()
    {
        return responseBodyFragments;
    }

    public int getStatus()
    {
        return status;
    }

    public void setStatus(int status)
    {
        this.status = status;
    }

    public long getDurationNanos()
    {
        return clientEndTs - clientStartTs;
    }

    public GatewayAttributes getAttributes()
    {
        return attributes;
    }

    public void setAttributes(GatewayAttributes attributes)
    {
        this.attributes = attributes;
    }

    /**
     * What the gateway recorded for the request body, which may be
     * {@link BodyChecksum#NOT_RECORDED}.
     */
    public BodyChecksum getJournaledRequestChecksum()
    {
        return journaledRequestChecksum;
    }

    /**
     * What the gateway recorded for the response body, which may be
     * {@link BodyChecksum#NOT_RECORDED}.
     */
    public BodyChecksum getJournaledResponseChecksum()
    {
        return journaledResponseChecksum;
    }

    public long getClientStartTs()
    {
        return clientStartTs;
    }

    public long getClientEndTs()
    {
        return clientEndTs;
    }

    public long getProxyStartTs()
    {
        return proxyStartTs;
    }

    public long getProxyFirstByteReceivedTs()
    {
        return proxyFirstByteReceivedTs;
    }

    public long getProxyEndTs()
    {
        return proxyEndTs;
    }

    public long getProxyDurationNanos()
    {
        return proxyEndTs - proxyStartTs;
    }

    public boolean wasProxied()
    {
        return getProxyStartTs() != -1;
    }

    public void setTraffic(long requestHeaderBytes, long requestBodyBytes, long responseHeaderBytes, long responseBodyBytes)
    {
        this.requestHeaderBytes = requestHeaderBytes;
        this.requestBodyBytes = requestBodyBytes;
        this.responseHeaderBytes = responseHeaderBytes;
        this.responseBodyBytes = responseBodyBytes;
    }

    public long getRequestHeaderBytes()
    {
        return requestHeaderBytes;
    }

    public long getRequestBodyBytes()
    {
        return requestBodyBytes;
    }

    public long getResponseHeaderBytes()
    {
        return responseHeaderBytes;
    }

    public long getResponseBodyBytes()
    {
        return responseBodyBytes;
    }

    public long getRequestTotalBytes()
    {
        return requestHeaderBytes + requestBodyBytes;
    }

    public long getResponseTotalBytes()
    {
        return responseHeaderBytes + responseBodyBytes;
    }

    public InetAddress remoteAddress()
    {
        return remoteAddress;
    }

    public IpSource getRemoteAddressSource()
    {
        return remoteAddressSource;
    }
}