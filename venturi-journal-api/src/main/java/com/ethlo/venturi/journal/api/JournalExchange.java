package com.ethlo.venturi.journal.api;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.ServerDirection;

/**
 * Stateful container for a single request/response lifecycle.
 * Maintains zero-copy integrity by storing body fragments as a list of slices.
 */
public final class JournalExchange
{
    private final String requestId;
    private final List<ByteBuffer> requestBodyFragments = new ArrayList<>();
    private final List<ByteBuffer> responseBodyFragments = new ArrayList<>();
    // Request data
    private String requestStartLine;
    private GatewayHeaders requestHeaders;
    // Response data
    private String responseStartLine;
    private GatewayHeaders responseHeaders;
    // Metrics
    private long timestamp;
    private int status;
    private long bytesSent;
    private long bytesReceived;
    private long durationNanos;

    public JournalExchange(String requestId)
    {
        this.requestId = requestId;
    }

    public void setRequest(String startLine, GatewayHeaders headers)
    {
        this.requestStartLine = startLine;
        this.requestHeaders = headers;
    }

    public void setResponse(String startLine, GatewayHeaders headers)
    {
        this.responseStartLine = startLine;
        this.responseHeaders = headers;
    }

    public void appendBody(ServerDirection dir, ByteBuffer fragment)
    {
        // We store the slice directly. No copies made.
        if (dir == ServerDirection.REQUEST)
        {
            requestBodyFragments.add(fragment);
        }
        else
        {
            responseBodyFragments.add(fragment);
        }
    }

    public void setMetrics(long ts, int status, long sent, long recv, long dur)
    {
        this.timestamp = ts;
        this.status = status;
        this.bytesSent = sent;
        this.bytesReceived = recv;
        this.durationNanos = dur;
    }

    public String getRequestId()
    {
        return requestId;
    }

    public String getRequestStartLine()
    {
        return requestStartLine;
    }

    public GatewayHeaders getRequestHeaders()
    {
        return requestHeaders;
    }

    public List<ByteBuffer> getRequestBodyFragments()
    {
        return requestBodyFragments;
    }

    public String getResponseStartLine()
    {
        return responseStartLine;
    }

    public GatewayHeaders getResponseHeaders()
    {
        return responseHeaders;
    }

    public List<ByteBuffer> getResponseBodyFragments()
    {
        return responseBodyFragments;
    }

    public long getTimestamp()
    {
        return timestamp;
    }

    public int getStatus()
    {
        return status;
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
        return durationNanos;
    }
}