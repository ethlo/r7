package com.ethlo.venturi.core.storage.mmap;

import com.ethlo.venturi.core.ServerDirection;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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
            // Now reading from the correct offset
            int dirIndex = buffer.getInt();
            ServerDirection dir = ServerDirection.values()[dirIndex];

            String reqId = readString(buffer);
            String startLine = readString(buffer);
            listener.onBegin(dir, reqId, startLine, readHeaders(buffer));
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
            String reqId = readString(buffer);
            listener.onEnd(reqId, buffer.getLong(), buffer.getInt(), buffer.getLong(), buffer.getLong(), buffer.getLong());
        }
    }

    private static Map<String, String> readHeaders(ByteBuffer buffer)
    {
        int count = buffer.getInt();
        if (count <= 0) return Collections.emptyMap();
        Map<String, String> headers = new HashMap<>(count);
        for (int i = 0; i < count; i++)
        {
            headers.put(readString(buffer), readString(buffer));
        }
        return headers;
    }

    private static String readString(ByteBuffer buffer)
    {
        int len = buffer.getInt();
        if (len < 0) return null;
        if (len == 0) return "";
        byte[] bytes = new byte[len];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static ByteBuffer readBuffer(ByteBuffer buffer)
    {
        int len = buffer.getInt();
        if (len < 0) return null;
        ByteBuffer slice = buffer.slice();
        slice.limit(len);
        buffer.position(buffer.position() + len);
        return slice;
    }
}