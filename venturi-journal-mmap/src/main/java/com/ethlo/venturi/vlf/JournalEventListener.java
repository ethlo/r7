package com.ethlo.venturi.vlf;

import java.nio.ByteBuffer;
import java.util.Map;

import com.ethlo.venturi.api.ServerDirection;

public interface JournalEventListener
{
    void onBegin(ServerDirection direction, String reqId, String startLine, Map<CharSequence, CharSequence> headers);

    void onBody(ServerDirection direction, String reqId, ByteBuffer body);

    void onEnd(String reqId, long timestamp, int status, long bytesSent, long bytesReceived, long durationNanos);
}