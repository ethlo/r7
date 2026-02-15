package com.ethlo.venturi.core.storage.mmap;

import java.nio.ByteBuffer;
import java.util.Map;

public interface JournalEventListener
{
    void onBegin(int direction, String reqId, String startLine, Map<String, String> headers);

    void onBody(String reqId, ByteBuffer body);

    void onEnd(String reqId, long timestamp, int status, long bytesSent, long bytesReceived, long durationNanos);
}