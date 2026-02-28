package com.ethlo.venturi.vlf;

import static com.ethlo.venturi.vlf.VlfConstants.MAGIC;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;

import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.ServerDirection;
import com.ethlo.venturi.journal.api.JournalLevel;
import com.ethlo.venturi.vlf.fbs.ClientRequest;
import com.ethlo.venturi.vlf.fbs.ClientResponse;
import com.ethlo.venturi.vlf.fbs.EndExchange;
import com.ethlo.venturi.vlf.fbs.EventPayload;
import com.ethlo.venturi.vlf.fbs.JournalEvent;
import com.ethlo.venturi.vlf.fbs.RequestBody;
import com.ethlo.venturi.vlf.fbs.ResponseBody;
import com.ethlo.venturi.vlf.fbs.UpstreamRequest;
import com.ethlo.venturi.vlf.fbs.UpstreamResponse;
import com.ethlo.venturi.vlf.model.ByteBufferAsciiFlyweight;

public final class JournalDecoder
{
    public static final JournalLevel[] JOURNAL_LEVELS = JournalLevel.values();
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

            parseEntryAndDispatch(buffer, listener);
        }

        return totalTextWeight;
    }

    private static void parseEntryAndDispatch(ByteBuffer buffer, JournalEventListener listener)
    {
        final int startPos = buffer.position();
        ByteBuffer fbSlice;
        ByteBuffer rawSlice = null;
        try
        {
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
                throw new IllegalStateException("Truncated entry");
            }

            CRC32C crc = new CRC32C();
            updateInt(crc, payloadLen);
            updateInt(crc, fbLen);
            updateInt(crc, rawLen);

            // ---- FlatBuffer slice (zero copy) ----
            fbSlice = buffer.slice();
            fbSlice.limit(fbLen);
            fbSlice.order(ByteOrder.LITTLE_ENDIAN);

            crc.update(fbSlice.duplicate());

            buffer.position(buffer.position() + fbLen);

            // ---- Raw slice ----
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
        }
        catch (IllegalArgumentException exc)
        {
            throw new IllegalStateException("Corrupt journal entry found at position " + startPos, exc);
        }

        final JournalEvent journalEvent = JournalEvent.getRootAsJournalEvent(fbSlice);

        dispatch(journalEvent, rawSlice, listener);
    }

    private static void updateInt(CRC32C crc, int value)
    {
        crc.update((value >>> 24) & 0xFF);
        crc.update((value >>> 16) & 0xFF);
        crc.update((value >>> 8) & 0xFF);
        crc.update(value & 0xFF);
    }


    private static void dispatch(final JournalEvent journalEvent, ByteBuffer buffer, JournalEventListener listener)
    {
        switch (journalEvent.eventType())
        {
            case EventPayload.ClientRequest ->
            {
                final ClientRequest ev = (ClientRequest) journalEvent.event(new ClientRequest());
                final CharSequence reqId = asAscii(ev.reqIdAsByteBuffer());
                final JournalLevel level = JOURNAL_LEVELS[ev.journalLevel()];
                final CharSequence startLine = asAscii(ev.startLineAsByteBuffer());
                final GatewayHeaders headers = new FbsGatewayHeaders(ev);
                listener.onClientRequest(reqId, level, startLine, headers);
            }

            case EventPayload.UpstreamRequest ->
            {
                final UpstreamRequest ev = (UpstreamRequest) journalEvent.event(new UpstreamRequest());
                final CharSequence reqId = asAscii(ev.reqIdAsByteBuffer());
                final JournalLevel level = JOURNAL_LEVELS[ev.journalLevel()];
                final CharSequence startLine = asAscii(ev.startLineAsByteBuffer());
                final GatewayHeaders headers = new FbsUpstreamRequestHeaders(ev);
                listener.onUpstreamRequest(reqId, level, startLine, headers);
            }

            case EventPayload.RequestBody ->
            {
                final RequestBody body = (RequestBody) journalEvent.event(new RequestBody());
                final CharSequence reqId = asAscii(body.reqIdAsByteBuffer());
                final int bodyLen = (int) body.length();
                final ByteBuffer bodyChunk = prepareBodyChunk(buffer, bodyLen);
                listener.onRequestBody(reqId, bodyChunk);
            }

            case EventPayload.UpstreamResponse ->
            {
                final UpstreamResponse ev = (UpstreamResponse) journalEvent.event(new UpstreamResponse());
                final CharSequence reqId = asAscii(ev.reqIdAsByteBuffer());
                final JournalLevel level = JOURNAL_LEVELS[ev.journalLevel()];
                final CharSequence startLine = asAscii(ev.startLineAsByteBuffer());
                final GatewayHeaders headers = new FbsUpstreamResponseHeaders(ev);
                listener.onUpstreamResponse(reqId, level, startLine, headers);
            }

            case EventPayload.ClientResponse ->
            {
                final ClientResponse ev = (ClientResponse) journalEvent.event(new ClientResponse());
                final CharSequence reqId = asAscii(ev.reqIdAsByteBuffer());
                final JournalLevel level = JOURNAL_LEVELS[ev.journalLevel()];
                final CharSequence startLine = asAscii(ev.startLineAsByteBuffer());
                final GatewayHeaders headers = new FbsClientResponseHeaders(ev);
                listener.onClientResponse(reqId, level, startLine, headers);
            }

            case EventPayload.ResponseBody ->
            {
                final ResponseBody body = (ResponseBody) journalEvent.event(new ResponseBody());
                final CharSequence reqId = asAscii(body.reqIdAsByteBuffer());
                final int bodyLen = (int) body.length();
                final ByteBuffer bodyChunk = prepareBodyChunk(buffer, bodyLen);
                listener.onResponseBody(reqId, bodyChunk);
            }

            case EventPayload.EndExchange ->
            {
                final EndExchange end = (EndExchange) journalEvent.event(new EndExchange());
                final CharSequence reqId = asAscii(end.reqIdAsByteBuffer());
                final long timestamp = end.timestamp();
                final int httpStatus = end.status();
                final long bytesSent = end.bytesSent();
                final long bytesRecieved = end.bytesReceived();
                final long duratioNanos = end.duration();
                final GatewayAttributes attributes = new FbsGatewayAttributes(end);

                listener.onEnd(reqId, attributes, timestamp, httpStatus, bytesSent, bytesRecieved, duratioNanos, end.requestCrc32c(), end.responseCrc32c());
            }

            default -> throw new IllegalStateException("Unknown event type: " + journalEvent.eventType());
        }
    }

    private static ByteBuffer prepareBodyChunk(ByteBuffer buffer, int bodyLen)
    {
        if (buffer.remaining() < bodyLen)
        {
            throw new IllegalStateException("Body length exceeds remaining buffer: " + bodyLen);
        }
        final ByteBuffer bodyChunk = buffer.slice();
        bodyChunk.limit(bodyLen);
        buffer.position(buffer.position() + bodyLen);
        return bodyChunk;
    }

    public static CharSequence asAscii(ByteBuffer buf)
    {
        if (buf == null)
        {
            return null;
        }
        return new ByteBufferAsciiFlyweight(buf, buf.position(), buf.remaining());
    }
}