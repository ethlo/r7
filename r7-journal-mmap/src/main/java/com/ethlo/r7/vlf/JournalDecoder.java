package com.ethlo.r7.vlf;

import static com.ethlo.r7.vlf.VlfConstants.MAGIC;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;

import com.ethlo.r7.api.GatewayAttributes;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.vlf.fbs.ClientRequest;
import com.ethlo.r7.vlf.fbs.ClientResponse;
import com.ethlo.r7.vlf.fbs.EndExchange;
import com.ethlo.r7.vlf.fbs.EventPayload;
import com.ethlo.r7.vlf.fbs.JournalEvent;
import com.ethlo.r7.vlf.fbs.RequestBody;
import com.ethlo.r7.vlf.fbs.ResponseBody;
import com.ethlo.r7.vlf.fbs.UpstreamRequest;
import com.ethlo.r7.vlf.fbs.UpstreamResponse;
import com.ethlo.r7.vlf.model.ByteBufferAsciiFlyweight;

public final class JournalDecoder
{
    public static final JournalLevel[] JOURNAL_LEVELS = JournalLevel.values();

    /**
     * Decodes the hybrid FlatBuffer + raw stream.
     */
    public static void decode(ByteBuffer buffer, JournalEventListener listener)
    {
        // Skip preamble
        if (buffer.position() == 0)
        {
            buffer.position(VlfConstants.PREAMBLE_SIZE);
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

    public static InetAddress fromByteBuffer(final ByteBuffer buffer)
    {
        if (buffer == null || buffer.remaining() == 0)
        {
            return null;
        }

        // Allocate exactly 4 (IPv4) or 16 (IPv6) bytes
        final byte[] ipBytes = new byte[buffer.remaining()];

        // Copy the bytes out of the FlatBuffer slice
        buffer.get(ipBytes);

        try
        {
            return InetAddress.getByAddress(ipBytes);
        }
        catch (final UnknownHostException e)
        {
            // This only happens if the byte array length is not exactly 4 or 16.
            throw new IllegalArgumentException("Invalid IP address byte length: " + ipBytes.length, e);
        }
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
                final InetAddress remoteAddress = fromByteBuffer(ev.clientIpAsByteBuffer());
                final IpSource ipSource = IpSource.valueOf(ev.clientIpSource());
                listener.onClientRequest(reqId, level, startLine, headers, remoteAddress, ipSource);
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

                // Defensive check: only proceed if we have a valid buffer for the claimed length
                if (bodyLen > 0 && buffer != null)
                {
                    final ByteBuffer bodyChunk = prepareBodyChunk(bodyLen, buffer);
                    listener.onRequestBody(reqId, bodyChunk);
                }
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
                // Defensive check: only proceed if we have a valid buffer for the claimed length
                if (bodyLen > 0 && buffer != null)
                {
                    final ByteBuffer bodyChunk = prepareBodyChunk(bodyLen, buffer);
                    listener.onResponseBody(reqId, bodyChunk);
                }
            }

            case EventPayload.EndExchange ->
            {
                final EndExchange end = (EndExchange) journalEvent.event(new EndExchange());
                final CharSequence reqId = asAscii(end.reqIdAsByteBuffer());
                final long clientStartTs = end.clientStart();
                final long clientEndTs = end.clientEnd();
                final long proxyStartTs = end.proxyStart();
                final long proxyFirstByteReceivedTs = end.proxyFirstByteReceived();
                final long proxyEnd = end.proxyEnd();
                final int httpStatus = end.status();
                final long requestHeaderBytes = end.requestHeaderBytes();
                final long requestBodyBytes = end.requestBodyBytes();
                final long responseHeaderBytes = end.responseHeaderBytes();
                final long responseBodyBytes = end.responseBodyBytes();
                final GatewayAttributes attributes = new FbsGatewayAttributes(end);

                listener.onEnd(reqId, attributes, clientStartTs, clientEndTs, httpStatus, requestHeaderBytes, requestBodyBytes, responseHeaderBytes, responseBodyBytes, proxyStartTs, proxyFirstByteReceivedTs, proxyEnd, (int) end.requestCrc32c(), (int) end.responseCrc32c());
            }

            default -> throw new IllegalStateException("Unknown event type: " + journalEvent.eventType());
        }
    }

    private static ByteBuffer prepareBodyChunk(int bodyLen, ByteBuffer buffer)
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