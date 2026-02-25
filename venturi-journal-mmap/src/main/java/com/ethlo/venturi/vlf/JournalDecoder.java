package com.ethlo.venturi.vlf;

import static com.ethlo.venturi.vlf.VlfConstants.MAGIC;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.zip.CRC32C;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.ServerDirection;
import com.ethlo.venturi.vlf.fbs.BodyEvent;
import com.ethlo.venturi.vlf.fbs.EndEvent;
import com.ethlo.venturi.vlf.fbs.EventPayload;
import com.ethlo.venturi.vlf.fbs.Header;
import com.ethlo.venturi.vlf.fbs.JournalEvent;
import com.ethlo.venturi.vlf.fbs.StartEvent;
import com.ethlo.venturi.vlf.model.ByteBufferAsciiFlyweight;

public final class JournalDecoder
{
    private static final ServerDirection[] SERVER_DIRECTIONS = ServerDirection.values();

    /**
     * Decodes the hybrid FlatBuffer + raw stream.
     */
    public static long decode(ByteBuffer buffer, JournalEventListener listener)
    {
        long totalTextWeight = 0;

        // Skip preamble
        if (buffer.position() == 0)
        {
            buffer.position(VlfConstants.PREAMBLE_SIZE); // 1024
        }

        while (buffer.hasRemaining())
        {
            final int startPos = buffer.position();

            // Peek at the byte WITHOUT advancing the position
            final byte marker = buffer.get(startPos);

            // If we hit a 0, we've reached the unwritten zero-padded tail of the segment
            if (marker == 0)
            {
                break;
            }

            try
            {
                parseEntry(buffer, listener);
            }
            catch (Exception e)
            {
                throw new RuntimeException("Parse error at position " + startPos + " with marker " + marker + ": " + e.getMessage(), e);
            }
        }

        return totalTextWeight;
    }

    private static void parseEntry(ByteBuffer buffer, JournalEventListener listener)
    {
        final int startPos = buffer.position();

        buffer.order(ByteOrder.BIG_ENDIAN);

        if (buffer.remaining() < 4 * Integer.BYTES)
        {
            throw new IllegalStateException("Incomplete header");
        }

        int magic = buffer.getInt();
        if (magic != MAGIC)
        {
            throw new IllegalStateException("Bad magic");
        }

        int payloadLen = buffer.getInt();
        int fbLen = buffer.getInt();
        int rawLen = buffer.getInt();

        if (payloadLen != (Integer.BYTES * 2 + fbLen + rawLen))
        {
            throw new IllegalStateException("Corrupt payload length");
        }

        if (buffer.remaining() < fbLen + rawLen + Integer.BYTES)
        {
            System.out.println("marker=" + buffer.get(startPos) + " remaining=" + buffer.remaining());
            throw new IllegalStateException("Truncated entry");
        }

        CRC32C crc = new CRC32C();
        updateInt(crc, payloadLen);
        updateInt(crc, fbLen);
        updateInt(crc, rawLen);

        // ---- FlatBuffer slice (zero copy) ----
        ByteBuffer fbSlice = buffer.slice();
        fbSlice.limit(fbLen);
        fbSlice.order(ByteOrder.LITTLE_ENDIAN);

        crc.update(fbSlice.duplicate());

        buffer.position(buffer.position() + fbLen);

        // ---- Raw slice ----
        ByteBuffer rawSlice = null;
        if (rawLen > 0)
        {
            rawSlice = buffer.slice();
            rawSlice.limit(rawLen);
            crc.update(rawSlice.duplicate());
            buffer.position(buffer.position() + rawLen);
        }

        int storedCrc = buffer.getInt();
        if ((int) crc.getValue() != storedCrc)
        {
            throw new IllegalStateException("CRC mismatch");
        }

        final JournalEvent je = JournalEvent.getRootAsJournalEvent(fbSlice);
        dispatch(je, rawSlice, listener);
    }

    private static void updateInt(CRC32C crc, int value)
    {
        crc.update((value >>> 24) & 0xFF);
        crc.update((value >>> 16) & 0xFF);
        crc.update((value >>> 8) & 0xFF);
        crc.update(value & 0xFF);
    }


    private static long dispatch(final JournalEvent journalEvent, ByteBuffer buffer, JournalEventListener listener)
    {
        long weight = 0;
        switch (journalEvent.eventType())
        {
            case EventPayload.StartEvent ->
            {
                StartEvent start = (StartEvent) journalEvent.event(new StartEvent());
                CharSequence reqId = asAscii(start.reqIdAsByteBuffer());
                ServerDirection dir = SERVER_DIRECTIONS[start.direction()];
                CharSequence startLine = asAscii(start.startLineAsByteBuffer());
                GatewayHeaders headers = new FbsGatewayHeaders(start);

                listener.onBegin(dir, reqId, startLine, headers);

                weight += safeLen(reqId) + dir.name().length() + safeLen(startLine) + headers.weight();
            }

            case EventPayload.BodyEvent ->
            {
                BodyEvent body = (BodyEvent) journalEvent.event(new BodyEvent());
                CharSequence reqId = asAscii(body.reqIdAsByteBuffer());
                ServerDirection dir = SERVER_DIRECTIONS[body.direction()];
                int bodyLen = (int) body.length();

                if (buffer.remaining() < bodyLen)
                    throw new IllegalStateException("Body length exceeds remaining buffer: " + bodyLen);

                ByteBuffer bodyChunk = buffer.slice();
                bodyChunk.limit(bodyLen);
                buffer.position(buffer.position() + bodyLen);

                listener.onBody(dir, reqId, bodyChunk);

                weight += safeLen(reqId) + dir.name().length() + bodyLen;
            }

            case EventPayload.EndEvent ->
            {
                EndEvent end = (EndEvent) journalEvent.event(new EndEvent());
                CharSequence reqId = asAscii(end.reqIdAsByteBuffer());
                long ts = end.timestamp();
                int status = end.status();
                long sent = end.bytesSent();
                long recv = end.bytesReceived();
                long duration = end.duration();

                listener.onEnd(reqId, ts, status, sent, recv, duration);

                weight += safeLen(reqId) + 60; // estimate numeric text weight
            }
            default -> throw new IllegalStateException("Unknown event type: " + journalEvent.eventType());
        }
        return weight;
    }

    private static int safeLen(CharSequence s)
    {
        return (s != null) ? s.length() : 0;
    }

    private static CharSequence asAscii(ByteBuffer buf)
    {
        if (buf == null)
        {
            return null;
        }
        return new ByteBufferAsciiFlyweight(buf, buf.position(), buf.remaining());
    }

    /**
     * Zero-allocation projection of StartEvent headers
     */
    private static class FbsGatewayHeaders implements GatewayHeaders
    {
        private final StartEvent event;
        private final int count;
        private final Header reusableHeader = new Header();

        FbsGatewayHeaders(StartEvent event)
        {
            this.event = event;
            this.count = event.headersLength();
        }

        @Override
        public CharSequence getFirst(CharSequence name)
        {
            for (int i = 0; i < count; i++)
            {
                event.headers(reusableHeader, i);
                if (charsEqual(name, asAscii(reusableHeader.nameAsByteBuffer())))
                {
                    return decodeUtf8(reusableHeader.valueAsByteBuffer());
                }
            }
            return null;
        }

        @Override
        public Iterable<CharSequence> getAll(CharSequence name)
        {
            return () -> new Iterator<>()
            {
                private int idx = 0;
                private CharSequence nextVal = null;

                @Override
                public boolean hasNext()
                {
                    if (nextVal != null) return true;
                    while (idx < count)
                    {
                        event.headers(reusableHeader, idx++);
                        if (charsEqual(name, asAscii(reusableHeader.nameAsByteBuffer())))
                        {
                            nextVal = decodeUtf8(reusableHeader.valueAsByteBuffer());
                            return true;
                        }
                    }
                    return false;
                }

                @Override
                public CharSequence next()
                {
                    if (!hasNext()) throw new NoSuchElementException();
                    CharSequence v = nextVal;
                    nextVal = null;
                    return v;
                }
            };
        }

        @Override
        public int forEach(EntryConsumer consumer)
        {
            for (int i = 0; i < count; i++)
            {
                event.headers(reusableHeader, i);
                consumer.accept(
                        asAscii(reusableHeader.nameAsByteBuffer()),
                        decodeUtf8(reusableHeader.valueAsByteBuffer())
                );
            }
            return count;
        }

        @Override
        public <S> int forEach(S state, StatefulEntryConsumer<S> consumer)
        {
            for (int i = 0; i < count; i++)
            {
                event.headers(reusableHeader, i);
                consumer.accept(
                        state,
                        asAscii(reusableHeader.nameAsByteBuffer()),
                        decodeUtf8(reusableHeader.valueAsByteBuffer())
                );
            }
            return count;
        }

        @Override
        public int weight()
        {
            int w = 0;
            for (int i = 0; i < count; i++)
            {
                event.headers(reusableHeader, i);
                w += reusableHeader.nameLength() + reusableHeader.valueLength();
            }
            return w;
        }

        private boolean charsEqual(CharSequence a, CharSequence b)
        {
            if (a == b) return true;
            if (a == null || b == null) return false;
            int len = a.length();
            if (len != b.length()) return false;
            for (int i = 0; i < len; i++)
            {
                if (a.charAt(i) != b.charAt(i)) return false;
            }
            return true;
        }

        private String decodeUtf8(ByteBuffer buf)
        {
            if (buf == null) return null;
            ByteBuffer tmp = buf.duplicate();
            return StandardCharsets.UTF_8.decode(tmp).toString();
        }

        @Override
        public void set(CharSequence name, CharSequence value)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void remove(CharSequence name)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void set(CharSequence name, Iterable<CharSequence> values)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void add(CharSequence name, CharSequence value)
        {
            throw new UnsupportedOperationException();
        }
    }
}