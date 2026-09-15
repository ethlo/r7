package com.ethlo.r7.r7f;

import static com.ethlo.r7.r7f.R7fConstants.MAGIC;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32C;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.api.GatewayAttributes;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.JournalIntegrityListener;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.r7f.fbs.ClientRequest;
import com.ethlo.r7.r7f.fbs.ClientResponse;
import com.ethlo.r7.r7f.fbs.EndExchange;
import com.ethlo.r7.r7f.fbs.EventPayload;
import com.ethlo.r7.r7f.fbs.JournalEvent;
import com.ethlo.r7.r7f.fbs.RequestBody;
import com.ethlo.r7.r7f.fbs.ResponseBody;
import com.ethlo.r7.r7f.fbs.UpstreamRequest;
import com.ethlo.r7.r7f.fbs.UpstreamResponse;

public final class JournalDecoder
{
    public static final JournalLevel[] JOURNAL_LEVELS = JournalLevel.values();

    private static final Logger logger = LoggerFactory.getLogger(JournalDecoder.class);

    /**
     * Cap on how much we are willing to scan looking for the next entry after hitting
     * damage, so a pathological file cannot turn a tail into a long linear scan.
     */
    private static final int MAX_RESYNC_SCAN = 1 << 20;

    /**
     * Passed as the expected sequence when the caller has no prior position in the
     * segment, so the first entry seen defines the starting point.
     */
    public static final int UNKNOWN_SEQUENCE = -1;

    private JournalDecoder()
    {
    }

    /**
     * Decodes the hybrid FlatBuffer + raw stream.
     * <p>
     * Damaged entries do not abort the file. Per FORMAT.md §6 the reader skips them,
     * resynchronising on the next entry magic, and reports what it skipped. Sequence
     * numbers are checked as we go, so a gap — entries that were written but never
     * reached the device — is reported rather than silently swallowed.
     *
     * @return what was decoded, skipped and found missing
     */
    public static DecodeStats decode(ByteBuffer buffer, JournalEventListener listener)
    {
        return decode(buffer, listener, UNKNOWN_SEQUENCE);
    }

    /**
     * As {@link #decode(ByteBuffer, JournalEventListener)}, but continuing a sequence that
     * started in an earlier call.
     * <p>
     * A reader that resumes mid-file — the tailer does, on every tick — would otherwise
     * adopt whatever sequence it happens to land on and never notice that entries went
     * missing across the resume boundary.
     *
     * @param expectedSequence the sequence number the first entry should carry, or
     *                         {@link #UNKNOWN_SEQUENCE} to adopt whatever is found first
     */
    public static DecodeStats decode(ByteBuffer buffer, JournalEventListener listener, int expectedSequence)
    {
        return decode(buffer, listener, expectedSequence, "<unnamed>", JournalIntegrityListener.NOOP, true);
    }

    /**
     * As {@link #decode(ByteBuffer, JournalEventListener, int)}, additionally reporting
     * damage and loss to an integrity listener as it is found.
     *
     * @param sourceName        name of the segment being read, for the integrity events
     * @param integrity         receives gap, corruption and regression events
     * @param preAllocatedTail  whether this source still carries the zero-filled remainder
     *                          of its pre-allocation. True for an active segment, where a
     *                          run of zeroes is the legitimate end of data. False for a
     *                          sealed segment, which recovery or rotation truncated to its
     *                          exact size — there, zeroes are not a tail but a region that
     *                          never reached the device, and the reader must look past them
     *                          for later entries rather than stop.
     */
    public static DecodeStats decode(ByteBuffer buffer,
                                     JournalEventListener listener,
                                     int expectedSequence,
                                     String sourceName,
                                     JournalIntegrityListener integrity,
                                     boolean preAllocatedTail)
    {
        // Skip preamble
        if (buffer.position() == 0)
        {
            buffer.position(R7fConstants.PREAMBLE_SIZE);
        }

        long entries = 0;
        long corruptEntriesSkipped = 0;
        long bytesSkipped = 0;
        long missingEntries = 0;
        int lastSequence = -1;

        while (buffer.remaining() >= R7fConstants.MIN_ENTRY_SIZE)
        {
            final int startPos = buffer.position();

            if (buffer.get(startPos) == 0)
            {
                if (preAllocatedTail)
                {
                    // The zero-filled remainder of the pre-allocation: no entry was ever
                    // written from here on.
                    break;
                }

                // A sealed segment is exactly the size of its data, so zeroes inside it are
                // not a tail — they are pages that never reached the device. Stopping here
                // is what made power-loss holes silent: the entries after the hole are
                // present and valid, and their sequence numbers are what prove the loss.
                final int afterHole = findNextEntry(buffer, startPos + 1, false);
                if (afterHole < 0)
                {
                    break;
                }

                final long holeBytes = afterHole - (long) startPos;
                logger.error("Unwritten region in sealed segment {} at offset {} ({} bytes); "
                        + "resuming at {}.", sourceName, startPos, holeBytes, afterHole);
                bytesSkipped += holeBytes;
                corruptEntriesSkipped++;
                integrity.onCorruptRegion(sourceName, startPos, holeBytes, "unwritten region in a sealed segment");
                buffer.position(afterHole);
                continue;
            }

            final Entry entry;
            try
            {
                entry = parseEntry(buffer);
            }
            catch (final CorruptEntryException | IllegalArgumentException | IndexOutOfBoundsException e)
            {
                buffer.position(startPos);
                final int resyncPos = findNextEntry(buffer, startPos + 1, preAllocatedTail);
                if (resyncPos < 0)
                {
                    final long skipped = buffer.limit() - startPos;
                    logger.warn("Corrupt entry at offset {} ({}); no further entries found, stopping.", startPos, e.getMessage());
                    buffer.position(buffer.limit());
                    bytesSkipped += skipped;
                    corruptEntriesSkipped++;
                    integrity.onCorruptRegion(sourceName, startPos, skipped, e.getMessage());
                    break;
                }

                logger.warn("Corrupt entry at offset {} ({}); skipping {} bytes and resuming at {}.",
                        startPos, e.getMessage(), resyncPos - startPos, resyncPos);
                bytesSkipped += resyncPos - startPos;
                corruptEntriesSkipped++;
                integrity.onCorruptRegion(sourceName, startPos, resyncPos - startPos, e.getMessage());
                buffer.position(resyncPos);
                // Any entries inside the skipped region are unknown; the sequence check
                // on the next successful entry accounts for them.
                continue;
            }

            if (expectedSequence != UNKNOWN_SEQUENCE && entry.sequence() != expectedSequence)
            {
                if (entry.sequence() > expectedSequence)
                {
                    final int lost = entry.sequence() - expectedSequence;
                    missingEntries += lost;
                    integrity.onEntriesMissing(sourceName, startPos, expectedSequence, entry.sequence(), lost);
                    logger.error("Sequence gap at offset {}: expected #{} but found #{} — {} entries missing.",
                            startPos, expectedSequence, entry.sequence(), lost);
                }
                else
                {
                    // FORMAT.md §6: a backward step means this is not a valid append-only
                    // segment. Continuing would replay duplicate or out-of-order events
                    // into the reassembler and build exchanges that never happened.
                    integrity.onSequenceRegression(sourceName, startPos, expectedSequence, entry.sequence());
                    logger.error("Sequence went backwards in {} at offset {}: expected #{} but found #{}. Stopping.",
                            sourceName, startPos, expectedSequence, entry.sequence());
                    buffer.position(startPos);
                    break;
                }
            }
            expectedSequence = entry.sequence() + 1;
            lastSequence = entry.sequence();

            try
            {
                final JournalEvent journalEvent = JournalEvent.getRootAsJournalEvent(entry.fbSlice());
                dispatch(journalEvent, entry.rawSlice(), listener);
                entries++;
            }
            catch (final CorruptEntryException | IllegalArgumentException | IndexOutOfBoundsException e)
            {
                // The framing and CRC were valid, so the bytes are what the writer wrote;
                // the payload itself is not something this build understands. Skip the
                // entry rather than abandon the rest of the file.
                logger.warn("Undecodable payload in entry #{} at offset {}: {}", entry.sequence(), startPos, e.getMessage());
                corruptEntriesSkipped++;
                integrity.onCorruptRegion(sourceName, startPos, 0L, "undecodable payload: " + e.getMessage());
            }
        }

        return new DecodeStats(entries, corruptEntriesSkipped, bytesSkipped, missingEntries, lastSequence);
    }

    /**
     * Parses one entry, leaving the buffer positioned immediately after it on success and
     * in an unspecified position on failure (the caller restores it).
     */
    private static Entry parseEntry(final ByteBuffer buffer)
    {
        buffer.order(ByteOrder.BIG_ENDIAN);

        if (buffer.remaining() < R7fConstants.ENTRY_HEADER_SIZE)
        {
            throw new CorruptEntryException("incomplete header");
        }

        final int magic = buffer.getInt();
        if (magic != MAGIC)
        {
            throw new CorruptEntryException("bad magic");
        }

        final int sequence = buffer.getInt();
        final int payloadLen = buffer.getInt();
        final int fbLen = buffer.getInt();
        final int rawLen = buffer.getInt();

        if (fbLen < 0 || rawLen < 0 || payloadLen != (Integer.BYTES * 2 + fbLen + rawLen))
        {
            throw new CorruptEntryException("corrupt payload length");
        }

        if (buffer.remaining() < (long) fbLen + rawLen + Integer.BYTES)
        {
            throw new CorruptEntryException("truncated entry");
        }

        final CRC32C crc = new CRC32C();
        updateInt(crc, sequence);
        updateInt(crc, payloadLen);
        updateInt(crc, fbLen);
        updateInt(crc, rawLen);

        // ---- FlatBuffer slice (zero copy) ----
        final ByteBuffer fbSlice = buffer.slice();
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

        final int storedCrc = buffer.getInt();
        if ((int) crc.getValue() != storedCrc)
        {
            throw new CorruptEntryException("CRC mismatch");
        }

        return new Entry(sequence, fbSlice, rawSlice);
    }

    /**
     * Scans forward for the next plausible entry magic.
     *
     * @param stopAtZero give up on reaching zero bytes, because in a source with a
     *                   pre-allocated tail they mean nothing was written from there on.
     *                   False for a sealed segment, where the scan must cross the hole.
     * @return the absolute position of the next entry, or -1 if none was found
     */
    private static int findNextEntry(final ByteBuffer buffer, final int from, final boolean stopAtZero)
    {
        final int limit = buffer.limit();
        final int end = (int) Math.min(limit - (long) R7fConstants.MIN_ENTRY_SIZE, from + (long) MAX_RESYNC_SCAN);
        final byte firstMagicByte = (byte) (MAGIC >>> 24);

        for (int pos = from; pos <= end; pos++)
        {
            final byte b = buffer.get(pos);
            if (b == 0 && stopAtZero)
            {
                return -1;
            }
            // Cheap first-byte test before the unaligned int read, so scanning a long
            // stretch of zeroes costs one byte comparison per position.
            if (b == firstMagicByte && buffer.getInt(pos) == MAGIC)
            {
                return pos;
            }
        }
        return -1;
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

    private static JournalLevel level(final int ordinal)
    {
        if (ordinal < 0 || ordinal >= JOURNAL_LEVELS.length)
        {
            throw new CorruptEntryException("journal level out of range: " + ordinal);
        }
        return JOURNAL_LEVELS[ordinal];
    }

    private static void dispatch(final JournalEvent journalEvent, ByteBuffer buffer, JournalEventListener listener)
    {
        switch (journalEvent.eventType())
        {
            case EventPayload.ClientRequest ->
            {
                final ClientRequest ev = (ClientRequest) journalEvent.event(new ClientRequest());
                final String reqId = asLatin1(ev.reqIdAsByteBuffer());
                final JournalLevel level = level(ev.journalLevel());
                final String startLine = asLatin1(ev.startLineAsByteBuffer());
                final GatewayHeaders headers = new FbsGatewayHeaders(ev);
                final InetAddress remoteAddress = fromByteBuffer(ev.clientIpAsByteBuffer());
                final IpSource ipSource = IpSource.valueOf(ev.clientIpSource());
                listener.onClientRequest(reqId, level, startLine, headers, remoteAddress, ipSource);
            }

            case EventPayload.UpstreamRequest ->
            {
                final UpstreamRequest ev = (UpstreamRequest) journalEvent.event(new UpstreamRequest());
                final String reqId = asLatin1(ev.reqIdAsByteBuffer());
                final JournalLevel level = level(ev.journalLevel());
                final String startLine = asLatin1(ev.startLineAsByteBuffer());
                final GatewayHeaders headers = new FbsUpstreamRequestHeaders(ev);
                listener.onUpstreamRequest(reqId, level, startLine, headers);
            }

            case EventPayload.RequestBody ->
            {
                final RequestBody body = (RequestBody) journalEvent.event(new RequestBody());
                final String reqId = asLatin1(body.reqIdAsByteBuffer());
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
                final String reqId = asLatin1(ev.reqIdAsByteBuffer());
                final JournalLevel level = level(ev.journalLevel());
                final String startLine = asLatin1(ev.startLineAsByteBuffer());
                final GatewayHeaders headers = new FbsUpstreamResponseHeaders(ev);
                listener.onUpstreamResponse(reqId, level, startLine, headers);
            }

            case EventPayload.ClientResponse ->
            {
                final ClientResponse ev = (ClientResponse) journalEvent.event(new ClientResponse());
                final String reqId = asLatin1(ev.reqIdAsByteBuffer());
                final JournalLevel level = level(ev.journalLevel());
                final String startLine = asLatin1(ev.startLineAsByteBuffer());
                final GatewayHeaders headers = new FbsClientResponseHeaders(ev);
                listener.onClientResponse(reqId, level, startLine, headers);
            }

            case EventPayload.ResponseBody ->
            {
                final ResponseBody body = (ResponseBody) journalEvent.event(new ResponseBody());
                final String reqId = asLatin1(body.reqIdAsByteBuffer());
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
                final String reqId = asLatin1(end.reqIdAsByteBuffer());
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

            default -> throw new CorruptEntryException("Unknown event type: " + journalEvent.eventType());
        }
    }

    private static ByteBuffer prepareBodyChunk(int bodyLen, ByteBuffer buffer)
    {
        if (buffer.remaining() < bodyLen)
        {
            throw new CorruptEntryException("Body length exceeds remaining buffer: " + bodyLen);
        }
        final ByteBuffer bodyChunk = buffer.slice();
        bodyChunk.limit(bodyLen);
        buffer.position(buffer.position() + bodyLen);
        return bodyChunk;
    }

    /**
     * Decodes as ISO-8859-1, which is the encoding HTTP header values and start lines are
     * carried in and the one the writer preserves byte-for-byte. Decoding as US-ASCII
     * here would map every byte above 127 to U+FFFD and silently corrupt the record.
     */
    public static String asLatin1(final ByteBuffer buf)
    {
        if (buf == null)
        {
            return null;
        }

        if (buf.hasArray())
        {
            // Zero-copy extraction of the backing array for heap buffers
            return new String(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining(), StandardCharsets.ISO_8859_1);
        }

        // Fallback for direct buffers
        final byte[] bytes = new byte[buf.remaining()];

        // Use duplicate() to avoid mutating the original buffer's position
        buf.duplicate().get(bytes);

        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    /**
     * @deprecated header values are latin-1, not ASCII; use {@link #asLatin1(ByteBuffer)}.
     */
    @Deprecated(forRemoval = true)
    public static String asAscii(final ByteBuffer buf)
    {
        return asLatin1(buf);
    }

    private record Entry(int sequence, ByteBuffer fbSlice, ByteBuffer rawSlice)
    {
    }

    /**
     * @param entries               entries successfully decoded and dispatched
     * @param corruptEntriesSkipped damaged regions skipped over
     * @param bytesSkipped          total bytes skipped while resynchronising
     * @param missingEntries        entries the sequence numbers prove are absent
     * @param lastSequence          sequence number of the last decoded entry, or -1
     */
    public record DecodeStats(long entries, long corruptEntriesSkipped, long bytesSkipped, long missingEntries, int lastSequence)
    {
        public boolean isClean()
        {
            return corruptEntriesSkipped == 0 && missingEntries == 0;
        }

        /**
         * The sequence a caller resuming after this batch should expect, or
         * {@link #UNKNOWN_SEQUENCE} if nothing was decoded.
         */
        public int nextExpectedSequence()
        {
            return lastSequence == UNKNOWN_SEQUENCE ? UNKNOWN_SEQUENCE : lastSequence + 1;
        }
    }

    static final class CorruptEntryException extends RuntimeException
    {
        CorruptEntryException(final String message)
        {
            super(message);
        }
    }
}
