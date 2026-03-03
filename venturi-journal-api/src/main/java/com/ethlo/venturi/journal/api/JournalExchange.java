package com.ethlo.venturi.journal.api;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayHeaders;

/**
 * Stateful container for a single request/response lifecycle.
 * Maintains zero-copy integrity by storing body fragments as a list of slices.
 */
public final class JournalExchange
{
    private final CharSequence requestId;
    private final List<ByteBuffer> requestBodyFragments = new ArrayList<>(0);
    private final List<ByteBuffer> responseBodyFragments = new ArrayList<>(0);

    // --- Slice 1: Pristine Ingress ---
    private CharSequence clientRequestStartLine;
    private GatewayHeaders clientRequestHeaders;
    private JournalLevel clientRequestLevel;

    // --- Slice 2: Upstream Intent ---
    private CharSequence upstreamRequestStartLine;
    private GatewayHeaders upstreamRequestHeaders;
    private JournalLevel upstreamRequestLevel;

    // --- Slice 3: Raw Backend Return ---
    private CharSequence upstreamResponseStartLine;
    private GatewayHeaders upstreamResponseHeaders;
    private JournalLevel upstreamResponseLevel;

    // --- Slice 4: Final Client Egress ---
    private CharSequence clientResponseStartLine;
    private GatewayHeaders clientResponseHeaders;
    private JournalLevel clientResponseLevel;

    // Metrics & Forensic Metadata
    private int status;
    private long bytesSent;
    private long bytesReceived;
    private GatewayAttributes attributes;
    private long journaledRequestCrc32;
    private long journaledResponseCrc32;
    private long clientStartTs;
    private long clientEndTs;
    private long proxyStartTs;
    private long proxyFirstByteReceivedTs;
    private long proxyEndTs;

    public JournalExchange(CharSequence requestId)
    {
        this.requestId = requestId;
    }

    /* ============================================================
       METADATA SETTERS (The Four Slices)
       ============================================================ */

    public void setClientRequest(CharSequence line, JournalLevel level, GatewayHeaders headers)
    {
        this.clientRequestStartLine = line;
        this.clientRequestLevel = level;
        this.clientRequestHeaders = headers;
    }

    public void setUpstreamRequest(CharSequence line, JournalLevel level, GatewayHeaders headers)
    {
        this.upstreamRequestStartLine = line;
        this.upstreamRequestLevel = level;
        this.upstreamRequestHeaders = headers;
    }

    public void setUpstreamResponse(CharSequence line, JournalLevel level, GatewayHeaders headers)
    {
        this.upstreamResponseStartLine = line;
        this.upstreamResponseLevel = level;
        this.upstreamResponseHeaders = headers;
    }

    public void setClientResponse(CharSequence line, JournalLevel level, GatewayHeaders headers)
    {
        this.clientResponseStartLine = line;
        this.clientResponseLevel = level;
        this.clientResponseHeaders = headers;
    }

    /* ============================================================
       BODY & METRICS
       ============================================================ */

    public void appendRequestBody(ByteBuffer fragment)
    {
        requestBodyFragments.add(fragment);
    }

    public void appendResponseBody(ByteBuffer fragment)
    {
        responseBodyFragments.add(fragment);
    }

    public void setMetrics(final long clientStartTs, final long clientEndTs, final long proxyStartTs, final long proxyFirstByteReceivedTs, final long proxyEndTs, final long bytesReceived, final long bytesSent)
    {
        this.clientStartTs = clientStartTs;
        this.clientEndTs = clientEndTs;
        this.proxyStartTs = proxyStartTs;
        this.proxyFirstByteReceivedTs = proxyFirstByteReceivedTs;
        this.proxyEndTs = proxyEndTs;
        this.bytesSent = bytesSent;
        this.bytesReceived = bytesReceived;
    }

    public void setJournalChecksums(long requestCrc32, long responseCrc32)
    {
        this.journaledRequestCrc32 = requestCrc32;
        this.journaledResponseCrc32 = responseCrc32;
    }

    public CharSequence getRequestId()
    {
        return requestId;
    }

    /* ============================================================
       GETTERS
       ============================================================ */

    // Request Slices
    public CharSequence getClientRequestStartLine()
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

    public CharSequence getUpstreamRequestStartLine()
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

    // Response Slices
    public CharSequence getUpstreamResponseStartLine()
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

    public CharSequence getClientResponseStartLine()
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

    // Body fragments
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

    public long getBytesSent()
    {
        return bytesSent;
    }

    public long getBytesReceived()
    {
        return bytesReceived;
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

    public long getJournaledRequestCrc32()
    {
        return journaledRequestCrc32;
    }

    public long getJournaledResponseCrc32()
    {
        return journaledResponseCrc32;
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
}