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
    private long timestamp;
    private int status;
    private long bytesSent;
    private long bytesReceived;
    private long durationNanos;
    private GatewayAttributes attributes;
    private long requestCrc32;
    private long responseCrc32;

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

    public void setMetrics(long ts, int status, long sent, long recv, long dur)
    {
        this.timestamp = ts;
        this.status = status;
        this.bytesSent = sent;
        this.bytesReceived = recv;
        this.durationNanos = dur;
    }

    public void setChecksums(long requestCrc32, long responseCrc32)
    {
        this.requestCrc32 = requestCrc32;
        this.responseCrc32 = responseCrc32;
    }

    public void setAttributes(GatewayAttributes attributes)
    {
        this.attributes = attributes;
    }

    /* ============================================================
       GETTERS
       ============================================================ */

    public CharSequence getRequestId() { return requestId; }

    // Request Slices
    public CharSequence getClientRequestStartLine() { return clientRequestStartLine; }
    public GatewayHeaders getClientRequestHeaders() { return clientRequestHeaders; }
    public JournalLevel getClientRequestLevel() { return clientRequestLevel; }

    public CharSequence getUpstreamRequestStartLine() { return upstreamRequestStartLine; }
    public GatewayHeaders getUpstreamRequestHeaders() { return upstreamRequestHeaders; }
    public JournalLevel getUpstreamRequestLevel() { return upstreamRequestLevel; }

    // Response Slices
    public CharSequence getUpstreamResponseStartLine() { return upstreamResponseStartLine; }
    public GatewayHeaders getUpstreamResponseHeaders() { return upstreamResponseHeaders; }
    public JournalLevel getUpstreamResponseLevel() { return upstreamResponseLevel; }

    public CharSequence getClientResponseStartLine() { return clientResponseStartLine; }
    public GatewayHeaders getClientResponseHeaders() { return clientResponseHeaders; }
    public JournalLevel getClientResponseLevel() { return clientResponseLevel; }

    // Body fragments
    public List<ByteBuffer> getRequestBodyFragments() { return requestBodyFragments; }
    public List<ByteBuffer> getResponseBodyFragments() { return responseBodyFragments; }

    // Metrics & Checks
    public long getTimestamp() { return timestamp; }
    public int getStatus() { return status; }
    public long getBytesSent() { return bytesSent; }
    public long getBytesReceived() { return bytesReceived; }
    public long getDurationNanos() { return durationNanos; }
    public GatewayAttributes getAttributes() { return attributes; }
    public long getRequestCrc32() { return requestCrc32; }
    public long getResponseCrc32() { return responseCrc32; }
}