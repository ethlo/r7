package com.ethlo.r7.r7f;

import static com.ethlo.r7.r7f.R7fConstants.MAGIC;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.invoke.VarHandle;
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
        // A caller with nothing to resume from is reading a segment from the beginning, and
        // the first entry of every segment is #1. Defaulting to UNKNOWN_SEQUENCE here handed
        // that caller the one thing the sequence numbers exist to prevent: entries lost from
        // the start of a segment become invisible, because the first survivor sets the
        // baseline and there is no earlier entry to contradict it.
        return decode(buffer, listener, R7fConstants.FIRST_ENTRY_SEQUENCE);
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
        // activeSegment is true because it is the safe assumption when the caller has not
        // said: treating an active segment as sealed consumes the bytes the writer is about
        // to fill and skips every entry appended afterwards, whereas treating a sealed
        // segment as active only stops early at a hole. Readers that know the file is final
        // — the tailer does — must call the full overload and pass false, or holes will not
        // be crossed.
        return decode(buffer, listener, expectedSequence, "<unnamed>", JournalIntegrityListener.NOOP, true);
    }

    /**
     * As {@link #decode(ByteBuffer, JournalEventListener, int)}, additionally reporting
     * damage and loss to an integrity listener as it is found.
     *
     * @param sourceName        name of the segment being read, for the integrity events
     * @param integrity         receives gap, corruption and regression events
     * @param activeSegment     whether the writer may still append to this source.
     *                          <p>
     *                          True for an active segment: a run of zeroes is the write
     *                          frontier, not damage, and the remainder is not the reader's
     *                          to consume or report. False for a sealed one, which the
     *                          caller has bounded at the Data End its seal record declares
     *                          — within that bound zeroes are a region that never reached
     *                          the device, and the reader must look past them for later
     *                          entries rather than stop.
     *                          <p>
     *                          This used to be called {@code preAllocatedTail}, which stopped
     *                          being the distinction when sealing stopped truncating: a
     *                          sealed segment carries a pre-allocated tail too. What matters
     *                          is who owns the bytes ahead, not whether they exist.
     */
    public static DecodeStats decode(ByteBuffer buffer,
                                     JournalEventListener listener,
                                     int expectedSequence,
                                     String sourceName,
                                     JournalIntegrityListener integrity,
                                     boolean activeSegment)
    {
        // The framing is big-endian; only the FlatBuffers payload is little-endian, and
        // parseEntry sets that on the slice it hands out. The caller maps the file
        // little-endian for FlatBuffers' benefit, so the order has to be established here
        // rather than inherited — parseEntry used to be the only thing that set it, which
        // left every absolute read before the first parseEntry call reading the wrong way
        // round.
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Skip preamble
        if (buffer.position() == 0)
        {
            buffer.position(R7fConstants.PREAMBLE_SIZE);
        }

        long entries = 0;
        long corruptEntriesSkipped = 0;
        long bytesSkipped = 0;
        long missingEntries = 0;
        long undeliveredBytes = 0;
        int lastSequence = -1;

        while (buffer.remaining() >= R7fConstants.MIN_ENTRY_SIZE)
        {
            final int startPos = buffer.position();

            if (buffer.get(startPos) == 0)
            {
                if (activeSegment)
                {
                    // No committed entry here. Either nothing was ever written from this
                    // point, or the writer is assembling an entry and has not stamped its
                    // magic yet — the two are the same thing to a reader, which is the
                    // whole point of stamping the magic last. Stop; come back next tick.
                    break;
                }

                // The caller bounded this buffer at the segment's declared Data End, so a
                // zero inside it is not a tail — it is a page that never reached the device.
                // Stopping here is what made power-loss holes silent: the entries after the
                // hole are present and valid, and their sequence numbers prove the loss.
                final int afterHole = findNextEntry(buffer, startPos + 1, false);
                if (afterHole < 0)
                {
                    // Nothing recognisable follows. Report it and consume the remainder:
                    // leaving the position where it is would let a caller that checkpoints
                    // by offset come back to the same bytes on every tick, for ever, while
                    // the damage stayed invisible.
                    final long trailing = buffer.limit() - (long) startPos;
                    logger.error("Sealed segment {} ends in {} unwritten bytes at offset {}.",
                            sourceName, trailing, startPos);
                    bytesSkipped += trailing;
                    corruptEntriesSkipped++;
                    integrity.onCorruptRegion(sourceName, startPos, trailing, "unwritten region at the end of a sealed segment");
                    buffer.position(buffer.limit());
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
                final int resyncPos = findNextEntry(buffer, startPos + 1, activeSegment);
                if (resyncPos < 0)
                {
                    if (activeSegment)
                    {
                        // A magic is present but the entry behind it does not parse. Since
                        // the writer stamps the magic last (FORMAT.md 5), this is not an
                        // entry caught mid-publish — an unpublished entry has a zero here and
                        // was handled above. So this is real damage in a file the writer
                        // still owns.
                        //
                        // Stop without consuming anyway. The remainder of an active segment
                        // is not ours to write off: consuming it would checkpoint the whole
                        // pre-allocation and skip every entry appended afterwards. Sealing
                        // makes the file final, and the branch below reports it then.
                        logger.warn("Corrupt entry at offset {} in active segment {} ({}); "
                                + "leaving it until the segment is sealed.", startPos, sourceName, e.getMessage());
                        buffer.position(startPos);
                        break;
                    }

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
                    // A sealed segment will never change, so leaving the position on the
                    // offending entry would have a caller that checkpoints by offset read
                    // and report it again on every tick. Consume the rest. An active
                    // segment is still being written, so there the position stays put.
                    if (!activeSegment)
                    {
                        // Account for what is being given up. This is the one branch that
                        // consumes bytes it could still have read, so silence here made the
                        // stats say the read was clean — and the tailer deletes a segment it
                        // believes it read in full. The remainder is abandoned, not absent,
                        // and has to be counted as such.
                        final long abandoned = buffer.limit() - (long) startPos;
                        bytesSkipped += abandoned;
                        undeliveredBytes += abandoned;
                        corruptEntriesSkipped++;
                        integrity.onCorruptRegion(sourceName, startPos, abandoned,
                                "abandoned after a sequence regression");
                        buffer.position(buffer.limit());
                    }
                    else
                    {
                        buffer.position(startPos);
                    }
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
            catch (final RuntimeException e)
            {
                // The framing and CRC were valid, so the bytes are what the writer wrote.
                // Past that point two different things can throw and they cannot be told
                // apart from here: the payload may not be something this build understands,
                // or the listener may have rejected it. FlatBuffers decodes lazily, so field
                // access happens inside dispatch, in the same call as the listener — there is
                // no seam between them to catch on.
                //
                // So the message says both, rather than asserting corruption on what may be
                // a bug in consumer code. What is not in doubt is that the entry must be
                // skipped and the read must continue: letting it out would leave this
                // segment's progress unrecorded and have every tick re-dispatch the same
                // entries for ever.
                logger.warn("Entry #{} at offset {} could not be delivered — the payload is "
                        + "undecodable or the listener rejected it: {}", entry.sequence(), startPos, e.toString());
                corruptEntriesSkipped++;
                integrity.onCorruptRegion(sourceName, startPos, 0L,
                        "entry not delivered (undecodable payload or listener failure): " + e);
            }
        }

        // The loop stops when fewer than MIN_ENTRY_SIZE bytes remain, and those bytes were
        // never examined by anything above. In a sealed segment that suffix is an entry cut
        // short — a stop partway through a write, or bytes lost after sealing — and leaving it
        // is the same failure every early exit in this method is written to avoid: the
        // tailer sees remaining() != 0, so the segment is never finished, and it checkpoints
        // the identical offset on every tick for ever while nothing is reported.
        //
        // An active segment keeps it: the writer may still be about to fill it, which is the
        // one case where standing still is progress (see the resync branch above).
        if (!activeSegment && buffer.hasRemaining())
        {
            final int trailingStart = buffer.position();
            final long trailing = buffer.remaining();
            logger.error("Sealed segment {} ends in {} bytes too short to hold an entry at offset {}.",
                    sourceName, trailing, trailingStart);
            bytesSkipped += trailing;
            corruptEntriesSkipped++;
            integrity.onCorruptRegion(sourceName, trailingStart, trailing,
                    "truncated entry at the end of a sealed segment");
            buffer.position(buffer.limit());
        }

        return new DecodeStats(entries, corruptEntriesSkipped, bytesSkipped, missingEntries, undeliveredBytes, lastSequence);
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

        // The writer stamps the magic last, behind a release fence (FORMAT.md 5). Pairing
        // an acquire here is what makes the rest of the entry guaranteed visible to this
        // reader — which, in production, is a different process sharing the mapping.
        VarHandle.acquireFence();

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
     * @param stopAtZero give up on reaching zero bytes, because in a segment the writer may
     *                   still append to they mean nothing has been written from there on.
     *                   False for a sealed segment, where the scan must cross the hole.
     * @return the absolute position of the next entry, or -1 if none was found
     */
    private static int findNextEntry(final ByteBuffer buffer, final int from, final boolean stopAtZero)
    {
        final int limit = buffer.limit();
        // A sealed segment is an audit record: scan it to the end rather than abandon a
        // suffix whose framing and CRC may be perfectly intact, as FORMAT.md §6 requires.
        // The cap applies only where the scan would otherwise run into a pre-allocated
        // tail, which the caller already stops at.
        final long lastStart = limit - (long) R7fConstants.MIN_ENTRY_SIZE;
        final int end = (int) (stopAtZero ? Math.min(lastStart, from + (long) MAX_RESYNC_SCAN) : lastStart);
        final byte firstMagicByte = (byte) (MAGIC >>> 24);

        for (int pos = from; pos <= end; pos++)
        {
            final byte b = buffer.get(pos);
            if (b == 0 && stopAtZero)
            {
                return -1;
            }
            // Cheap first-byte test before the rest, so scanning a long stretch of zeroes
            // costs one byte comparison per position.
            //
            // The remaining three bytes are compared individually rather than with getInt:
            // this is the method that finds the way back after damage, and it must not
            // depend on the buffer's current byte order to do it. It silently found nothing
            // on a little-endian buffer, which turned "resynchronise past the hole" into
            // "this segment ends here" — and the caller then consumed and deleted a sealed
            // segment whose surviving entries had never been read.
            if (b == firstMagicByte
                    && buffer.get(pos + 1) == (byte) (MAGIC >>> 16)
                    && buffer.get(pos + 2) == (byte) (MAGIC >>> 8)
                    && buffer.get(pos + 3) == (byte) MAGIC)
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

                listener.onEnd(reqId, attributes, clientStartTs, clientEndTs, httpStatus, requestHeaderBytes, requestBodyBytes, responseHeaderBytes, responseBodyBytes, proxyStartTs, proxyFirstByteReceivedTs, proxyEnd, end.requestCrc32c(), end.responseCrc32c());
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
    /**
     * @param bytesSkipped   everything the reader passed over, damaged or abandoned
     * @param undeliveredBytes the part of {@code bytesSkipped} that was <em>readable</em> and
     *                       deliberately not delivered — today only the remainder after a
     *                       sequence regression, where the reader stops because the segment
     *                       is no longer a valid append-only log. Damage is different in
     *                       kind: those bytes are gone whatever anyone does, whereas these
     *                       are still there and still decodable, so destroying the segment
     *                       would destroy deliverable records.
     *                       <p>
     *                       Not to be confused with {@code ExchangeCompletionListener.onAbandoned},
     *                       which is about an exchange aged out of the reassembler. This is
     *                       about bytes in a segment, and the two are unrelated — which is
     *                       why this is not called "abandoned".
     */
    public record DecodeStats(long entries, long corruptEntriesSkipped, long bytesSkipped, long missingEntries, long undeliveredBytes, int lastSequence)
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
