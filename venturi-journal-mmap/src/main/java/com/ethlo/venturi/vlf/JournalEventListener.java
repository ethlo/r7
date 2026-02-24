package com.ethlo.venturi.vlf;

import java.nio.ByteBuffer;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.ServerDirection;

public interface JournalEventListener
{
    void onBegin(ServerDirection direction, CharSequence reqId, CharSequence startLine, GatewayHeaders headers);

    void onBody(ServerDirection direction, CharSequence reqId, ByteBuffer body);

    void onEnd(CharSequence reqId, long timestamp, int status, long bytesSent, long bytesReceived, long durationNanos);
}