package com.ethlo.venturi.core.storage.mmap;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.ethlo.venturi.core.ServerDirection;

public final class JournalDecoder
{
    public static void decode(ByteBuffer buffer, JournalEventListener listener)
    {
        while (buffer.hasRemaining())
        {
            int startPos = buffer.position();
            byte marker = buffer.get(); // Consumes the marker byte

            if (marker == 0) // End of written data in shard
            {
                buffer.position(startPos);
                break;
            }

            parseEvent(marker, buffer, listener);
        }
    }

    private static void parseEvent(byte marker, ByteBuffer buffer, JournalEventListener listener)
    {
        if (marker == Marker.BEGIN)
        {
            int dirIndex = buffer.getInt();
            ServerDirection dir = ServerDirection.values()[dirIndex];
            String requestId = readString(buffer);
            String startLine = readString(buffer);
            listener.onBegin(dir, requestId, startLine, readHeaders(buffer));
        }
        else if (marker == Marker.BODY)
        {
            ServerDirection dir = ServerDirection.values()[buffer.getInt()];
            String reqId = readString(buffer);
            ByteBuffer body = readBuffer(buffer);
            if (body != null) listener.onBody(dir, reqId, body);
        }
        else if (marker == Marker.END)
        {
            String requestId = readString(buffer);
            listener.onEnd(requestId, buffer.getLong(), buffer.getInt(), buffer.getLong(), buffer.getLong(), buffer.getLong());
        }
    }

    private static Map<String, String> readHeaders(ByteBuffer buffer)
    {
        final int count = buffer.getInt();
        if (count <= 0)
        {
            return Collections.emptyMap();
        }
        final Map<String, String> headers = new HashMap<>(count);
        for (int i = 0; i < count; i++)
        {
            headers.put(readString(buffer), readString(buffer));
        }
        return headers;
    }

    private static String readString(ByteBuffer buffer)
    {
        final int len = buffer.getInt();
        if (len < 0)
        {
            return null;
        }
        if (len == 0)
        {
            return "";
        }
        final byte[] bytes = new byte[len];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static ByteBuffer readBuffer(ByteBuffer buffer)
    {
        final int len = buffer.getInt();
        if (len < 0)
        {
            return null;
        }
        final ByteBuffer slice = buffer.slice();
        slice.limit(len);
        buffer.position(buffer.position() + len);
        return slice;
    }
}