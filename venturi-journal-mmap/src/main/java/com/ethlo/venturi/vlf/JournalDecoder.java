package com.ethlo.venturi.vlf;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.ServerDirection;
import com.ethlo.venturi.util.FastGatewayHeaders;
import com.ethlo.venturi.vlf.dictionary.VlfDictionary;
import com.ethlo.venturi.vlf.dictionary.VlfDictionaryByteUltra;

public final class JournalDecoder
{
    public static long decode(ByteBuffer buffer, VlfDictionary dictionary, JournalEventListener listener)
    {
        long totalTextWeight = 0;
        while (buffer.hasRemaining())
        {
            final int startPos = buffer.position();
            final byte marker = buffer.get();

            // 0 is the padding in mmap files
            if (marker == VlfConstants.NULL_VALUE)
            {
                buffer.position(startPos);
                break;
            }

            try
            {
                totalTextWeight += parseEvent(marker, buffer, dictionary, listener);
            }
            catch (Exception e)
            {
                throw new RuntimeException("Parse error at position " + startPos + " with marker " + marker, e);
            }
        }
        return totalTextWeight;
    }

    private static long parseEvent(byte marker, ByteBuffer buffer, VlfDictionary dictionary, JournalEventListener listener)
    {
        long weight = 0;
        if (marker == VlfConstants.MARKER_START)
        {
            final String requestId = readReqId(buffer);
            final ServerDirection dir = ServerDirection.values()[buffer.get()];
            final String startLine = readPrefixedBufferAsString(buffer);
            final GatewayHeaders headers = readHeaders(buffer, dictionary);

            listener.onBegin(dir, requestId, startLine, headers);

            weight += safeLen(requestId) + safeLen(dir.name()) + safeLen(startLine);
            weight += headers.weight();
        }
        else if (marker == VlfConstants.MARKER_BODY)
        {
            final String requestId = readReqId(buffer);
            final ServerDirection dir = ServerDirection.values()[buffer.get()];
            final ByteBuffer body = readPrefixedBuffer(buffer);
            final int bodyLen = (body != null) ? body.remaining() : 0;

            listener.onBody(dir, requestId, body);
            weight += safeLen(requestId) + safeLen(dir.name()) + bodyLen;
        }
        else if (marker == VlfConstants.MARKER_END)
        {
            final String requestId = readReqId(buffer);
            final long ts = buffer.getLong();
            final int status = buffer.getInt();
            final long sent = buffer.getLong();
            final long recv = buffer.getLong();
            final long duration = buffer.getLong();

            listener.onEnd(requestId, ts, status, sent, recv, duration);
            weight += safeLen(requestId) + 60; // Estimated numeric text weight
        }
        return weight;
    }

    private static int safeLen(CharSequence s)
    {
        return (s != null) ? s.length() : 0;
    }

    private static CharSequence readString(ByteBuffer buffer, VlfDictionary dictionary)
    {
        final byte first = buffer.get();
        if (first == VlfConstants.DICT_LOOKUP)
        {
            return dictionary.decode(buffer.get());
        }
        else if (first == VlfConstants.LONG_STRING)
        {
            return readRawString(buffer, buffer.getInt());
        }
        else if (first == VlfConstants.NULL_VALUE)
        {
            return null;
        }
        return readRawString(buffer, first & 0xFF);
    }

    private static String readReqId(ByteBuffer buffer)
    {
        final int len = Byte.toUnsignedInt(buffer.get());
        final byte[] bytes = new byte[len];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String readRawString(ByteBuffer buffer, int len)
    {
        final byte[] bytes = new byte[len];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String readPrefixedBufferAsString(ByteBuffer buffer)
    {
        return readRawString(buffer, buffer.getInt());
    }

    private static ByteBuffer readPrefixedBuffer(ByteBuffer buffer)
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

    private static GatewayHeaders readHeaders(ByteBuffer buffer, VlfDictionary dictionary)
    {
        final int count = buffer.getInt();
        final GatewayHeaders headers = new FastGatewayHeaders();
        for (int i = 0; i < count; i++)
        {
            final CharSequence k = readString(buffer, dictionary);
            final CharSequence v = readString(buffer, dictionary);
            headers.add(k, v);
        }
        return headers;
    }

    public static VlfDictionary readDictionaryFromPreamble(ByteBuffer buffer)
    {
        buffer.position(0);
        if (buffer.getInt() != VlfConstants.MAGIC)
        {
            throw new IllegalStateException("Invalid Magic");
        }
        if (buffer.getShort() != VlfConstants.VERSION_1)
        {
            throw new IllegalStateException("Invalid Version");
        }
        final short entryCount = buffer.getShort();
        final Properties props = new Properties();
        for (int i = 0; i < entryCount; i++)
        {
            final int id = Byte.toUnsignedInt(buffer.get());
            final int len = Byte.toUnsignedInt(buffer.get());
            final byte[] b = new byte[len];
            buffer.get(b);
            props.setProperty(String.valueOf(id), new String(b, StandardCharsets.UTF_8));
        }
        return new VlfDictionaryByteUltra(props);
    }
}