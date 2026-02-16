package com.ethlo.venturi.vlf;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.ethlo.venturi.api.ServerDirection;

public final class JournalDecoder
{
    public static void decode(ByteBuffer buffer, JournalEventListener listener)
    {
        // If we are at the very start of the shard, validate the protocol version
        if (buffer.position() == 0 && buffer.hasRemaining())
        {
            byte version = buffer.get();
            if (version != Marker.VERSION)
            {
                throw new IllegalStateException("Invalid protocol version! Expected " + Marker.VERSION + " but got " + version + " at position 0");
            }
        }

        while (buffer.hasRemaining())
        {
            int startPos = buffer.position();
            byte marker = buffer.get();

            // 0 is the ONLY valid "silent" stop - it means we hit the pre-allocated zeroed space
            if (marker == 0)
            {
                buffer.position(startPos);
                break;
            }

            try
            {
                parseEvent(marker, buffer, listener);
            }
            catch (Exception e)
            {
                throw new RuntimeException("Failed to parse event at position " + startPos + " with marker " + marker + " in " + buffer, e);
            }
        }
    }

    private static void parseEvent(byte marker, ByteBuffer buffer, JournalEventListener listener)
    {
        if (marker == Marker.BEGIN)
        {
            String requestId = readReqId(buffer);
            int dirOrdinal = buffer.getInt();
            validateDirection(dirOrdinal);
            ServerDirection dir = ServerDirection.values()[dirOrdinal];
            String startLine = readPrefixedString(buffer);
            listener.onBegin(dir, requestId, startLine, readHeaders(buffer));
        }
        else if (marker == Marker.BODY)
        {
            String requestId = readReqId(buffer);
            int dirOrdinal = buffer.getInt();
            validateDirection(dirOrdinal);
            ServerDirection dir = ServerDirection.values()[dirOrdinal];
            ByteBuffer body = readPrefixedBuffer(buffer);
            if (body != null)
            {
                listener.onBody(dir, requestId, body);
            }
        }
        else if (marker == Marker.END)
        {
            String requestId = readReqId(buffer);
            listener.onEnd(requestId, buffer.getLong(), buffer.getInt(),
                    buffer.getLong(), buffer.getLong(), buffer.getLong()
            );
        }
        else
        {
            throw new IllegalArgumentException("Unknown marker: " + marker + " at position " + (buffer.position() - 1));
        }
    }

    private static String readReqId(ByteBuffer buffer)
    {
        if (!buffer.hasRemaining()) throw new IllegalStateException("Missing ReqID length byte");
        int len = Byte.toUnsignedInt(buffer.get());
        if (buffer.remaining() < len) throw new IllegalStateException("Incomplete ReqID. Expected " + len + " bytes");
        byte[] bytes = new byte[len];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String readPrefixedString(ByteBuffer buffer)
    {
        if (buffer.remaining() < 4) throw new IllegalStateException("Missing String length prefix");
        int len = buffer.getInt();
        if (len < 0) return null;
        if (buffer.remaining() < len) throw new IllegalStateException("Incomplete String. Expected " + len + " bytes");
        byte[] bytes = new byte[len];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static ByteBuffer readPrefixedBuffer(ByteBuffer buffer)
    {
        if (buffer.remaining() < 4) throw new IllegalStateException("Missing Buffer length prefix");
        int len = buffer.getInt();
        if (len < 0) return null;
        if (buffer.remaining() < len) throw new IllegalStateException("Incomplete Buffer. Expected " + len + " bytes");
        ByteBuffer slice = buffer.slice();
        slice.limit(len);
        buffer.position(buffer.position() + len);
        return slice;
    }

    private static Map<String, String> readHeaders(ByteBuffer buffer)
    {
        if (buffer.remaining() < 4) throw new IllegalStateException("Missing Header count");
        int count = buffer.getInt();
        if (count <= 0) return Collections.emptyMap();
        Map<String, String> headers = new HashMap<>(count);
        for (int i = 0; i < count; i++)
        {
            headers.put(readPrefixedString(buffer), readPrefixedString(buffer));
        }
        return headers;
    }

    private static void validateDirection(int ordinal)
    {
        if (ordinal < 0 || ordinal >= ServerDirection.values().length)
        {
            throw new IllegalArgumentException("Invalid ServerDirection ordinal: " + ordinal);
        }
    }
}