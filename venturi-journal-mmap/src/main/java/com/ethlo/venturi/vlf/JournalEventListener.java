package com.ethlo.venturi.vlf;

import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.ServerDirection;
import com.ethlo.venturi.journal.api.JournalLevel;

import java.nio.ByteBuffer;

public interface JournalEventListener
{
    void onBegin(ServerDirection direction, final JournalLevel journalLevel, CharSequence reqId, CharSequence startLine, GatewayHeaders headers);

    void onBody(ServerDirection direction, CharSequence reqId, ByteBuffer body);

    void onEnd(CharSequence reqId, GatewayAttributes attributes, long timestamp, int status, long bytesSent, long bytesReceived, long durationNanos);
}