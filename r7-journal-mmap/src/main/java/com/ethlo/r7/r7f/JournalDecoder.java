package com.ethlo.r7.r7f;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.api.GatewayAttributes;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.JournalIntegrityListener;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.r7f.fbs.ClientRequest;
import com.ethlo.r7.r7f.fbs.ClientResponse;
import com.ethlo.r7.r7f.fbs.EndExchange;
import com.ethlo.r7.r7f.fbs.EventPayload;
import com.ethlo.r7.r7f.fbs.HeaderDelta;
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
     * resynchronising at the next block boundary, and reports what it skipped. Sequence
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
        // Skip preamble
        if (buffer.position() == 0)
        {
            buffer.position(R7fConstants.PREAMBLE_SIZE);
        }

        // Block boundaries are file offsets, which is why the buffer has to start at the
        // file's first byte. The block size is the segment's own, from its preamble.
        final int blockSize = buffer.duplicate().order(ByteOrder.BIG_ENDIAN).getInt(R7fConstants.PREAMBLE_OFF_BLOCK_SIZE);
        if (!R7fFraming.isValidBlockSize(blockSize))
        {
            throw new IllegalArgumentException("Segment " + sourceName + " declares an invalid block size of "
                    + blockSize + " bytes; it is not a version " + R7fConstants.CURRENT_VERSION + " segment");
        }
        final FragmentReader reader = new FragmentReader(buffer, buffer.limit(), blockSize);
        final int limit = buffer.limit();

        // Everything the consumer throws comes back wrapped, so that a consumer failure and
        // an undecodable payload — which are otherwise indistinguishable, because FlatBuffers
        // decodes lazily and the field access happens inside the listener call — are handled
        // by different branches below.
        final JournalEventListener guarded = new DeliveryGuard(listener);

        long entries = 0;
        long corruptEntriesSkipped = 0;
        long bytesSkipped = 0;
        long missingEntries = 0;
        long undeliveredBytes = 0;
        int lastSequence = -1;

        // A damaged region in a sealed segment, open until the next entry is found or the data
        // ends. Resynchronising can take several steps — a hole, then the orphaned tail of the
        // entry it cut, then more zeroes — and they are one loss, so they are reported as one.
        long damageStart = -1;
        String damageReason = null;

        while (true)
        {
            final int startPos = buffer.position();
            final FragmentReader.Status status = reader.read(startPos);

            if (status != FragmentReader.Status.ENTRY)
            {
                if (activeSegment)
                {
                    if (status == FragmentReader.Status.DAMAGED)
                    {
                        // A magic is present but the entry behind it does not hold up. Since
                        // the writer stamps the commit magic last (FORMAT.md 5.1), this is not
                        // an entry caught mid-publish — an unpublished entry reads as a zero.
                        // So this is real damage in a file the writer still owns.
                        //
                        // Stop without consuming anyway. The remainder of an active segment
                        // is not ours to write off: consuming it would checkpoint the whole
                        // pre-allocation and skip every entry appended afterwards. Sealing
                        // makes the file final, and the branches below report it then.
                        logger.warn("Corrupt entry at offset {} in active segment {} ({}); "
                                + "leaving it until the segment is sealed.", reader.entryStart(), sourceName, reader.problem());
                    }
                    // Otherwise the write frontier: nothing committed here yet. Come back next
                    // tick; the position stays where it is.
                    buffer.position(startPos);
                    break;
                }

                if (status == FragmentReader.Status.END)
                {
                    if (startPos < limit && damageStart < 0)
                    {
                        // Bytes before the declared end that cannot hold even a fragment. A
                        // writer never ends a segment that way, so something was cut short.
                        damageStart = startPos;
                        damageReason = "truncated entry at the end of a sealed segment";
                    }
                    break;
                }

                // The caller bounded this buffer at the segment's declared Data End, so a zero
                // inside it is not a tail — it is a page that never reached the device — and a
                // broken entry is damage. Either way the entries after it are present and their
                // sequence numbers prove the loss, so stopping here is what would make the loss
                // silent. Resume at the next block boundary, never by looking for a magic
                // (FORMAT.md 6.1).
                if (damageStart < 0)
                {
                    damageStart = startPos;
                    damageReason = status == FragmentReader.Status.UNWRITTEN
                            ? "unwritten region in a sealed segment"
                            : reader.problem();
                }
                final long resume = reader.resync(reader.entryStart());
                buffer.position((int) resume);
                if (resume >= limit)
                {
                    break;
                }
                continue;
            }

            if (damageStart >= 0)
            {
                final long skipped = reader.entryStart() - damageStart;
                logger.warn("Damaged region in {} at offset {} ({}); skipped {} bytes and resumed at {}.",
                        sourceName, damageStart, damageReason, skipped, reader.entryStart());
                bytesSkipped += skipped;
                corruptEntriesSkipped++;
                integrity.onCorruptRegion(sourceName, damageStart, skipped, damageReason);
                damageStart = -1;
            }

            final int sequence = reader.sequence();
            if (expectedSequence != UNKNOWN_SEQUENCE && sequence != expectedSequence)
            {
                if (sequence > expectedSequence)
                {
                    final int lost = sequence - expectedSequence;
                    missingEntries += lost;
                    integrity.onEntriesMissing(sourceName, startPos, expectedSequence, sequence, lost);
                    logger.error("Sequence gap at offset {}: expected #{} but found #{} — {} entries missing.",
                            startPos, expectedSequence, sequence, lost);
                }
                else
                {
                    // FORMAT.md §6: a backward step means this is not a valid append-only
                    // segment. Continuing would replay duplicate or out-of-order events
                    // into the reassembler and build exchanges that never happened.
                    integrity.onSequenceRegression(sourceName, startPos, expectedSequence, sequence);
                    logger.error("Sequence went backwards in {} at offset {}: expected #{} but found #{}. Stopping.",
                            sourceName, startPos, expectedSequence, sequence);
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
                        final long abandoned = limit - (long) startPos;
                        bytesSkipped += abandoned;
                        undeliveredBytes += abandoned;
                        corruptEntriesSkipped++;
                        integrity.onCorruptRegion(sourceName, startPos, abandoned,
                                "abandoned after a sequence regression");
                        buffer.position(limit);
                    }
                    else
                    {
                        buffer.position(startPos);
                    }
                    break;
                }
            }

            try
            {
                final JournalEvent journalEvent = JournalEvent.getRootAsJournalEvent(reader.fbSlice());
                dispatch(journalEvent, reader.rawSlice(), guarded);
                entries++;
            }
            catch (final ListenerFailureException e)
            {
                // The consumer refused this entry. Skipping it would consume it: the caller
                // checkpoints past it, the segment eventually reads as fully processed, and
                // the tailer deletes it — so a sink that was unavailable for one tick costs a
                // valid exchange, permanently. That is exactly the loss this journal exists
                // to make impossible.
                //
                // So the reader stops here and gives up nothing. Position goes back to the
                // start of the entry, the sequence expectation is not advanced, and the next
                // pass offers the same entry again. Nothing later in this segment is read
                // until it is accepted, which is head-of-line blocking on purpose: an audit
                // log may stall loudly, but it may not skip.
                //
                // Note the one ambiguity this cannot resolve. FlatBuffers decodes lazily, so
                // a consumer that touches a header or attribute pulls bytes at that moment;
                // an undecodable payload can therefore surface as a listener failure and
                // stall a segment that will never decode. That is the safe direction of the
                // error — a stall names the segment, offset and sequence on every tick and
                // destroys nothing, whereas the opposite mistake is silent and permanent.
                buffer.position(startPos);
                logger.error("Entry #{} at offset {} in {} was refused by the consumer; the segment "
                                + "stops here and will be offered again. Nothing after it is read until "
                                + "it is accepted.",
                        sequence, startPos, sourceName, e.getCause());
                integrity.onDeliveryStalled(sourceName, startPos, sequence, e.getCause());
                break;
            }
            catch (final RuntimeException e)
            {
                // The framing and CRCs were valid, so the bytes are what the writer wrote, but
                // they are not something this build can decode — an event type it does not
                // know, or a payload whose internal offsets do not hold up. The consumer is
                // not implicated: everything it threw arrived as ListenerFailureException
                // above.
                //
                // Skip it and continue. Letting it out would leave this segment's progress
                // unrecorded and have every tick re-dispatch the same entries for ever, and
                // unlike a refusal there is nothing a later attempt would do differently.
                logger.warn("Entry #{} at offset {} holds a payload this build cannot decode: {}",
                        sequence, startPos, e.toString());
                corruptEntriesSkipped++;
                integrity.onCorruptRegion(sourceName, startPos, 0L, "undecodable payload: " + e);
            }

            // After delivery, not before. A refusal above leaves the entry unread, and the
            // caller checkpoints the sequence alongside the offset — advancing it here would
            // record "next expected #8" against an offset pointing at #8, and the retry would
            // then read #8 as a sequence regression and abandon the rest of the segment.
            expectedSequence = sequence + 1;
            lastSequence = sequence;
            buffer.position((int) reader.entryEnd());
        }

        // A sealed segment is final, so a damaged region still open here runs to the declared
        // end, and the whole of it is consumed. Leaving it would be the failure every exit in
        // this method is written to avoid: the tailer sees remaining() != 0, so the segment is
        // never finished, and it checkpoints the identical offset on every tick for ever while
        // nothing is reported.
        //
        // Only an open region is consumed here. A delivery stall deliberately rewinds to the
        // start of an entry it means to offer again, and closed any region before it got there;
        // consuming on hasRemaining() alone would turn that rewind into a skip, and the segment
        // would be declared read in full and deleted with the refused entry in it.
        if (damageStart >= 0)
        {
            final long skipped = limit - damageStart;
            logger.error("Damaged region at the end of sealed segment {} at offset {} ({}); {} bytes, "
                    + "no further entries.", sourceName, damageStart, damageReason, skipped);
            bytesSkipped += skipped;
            corruptEntriesSkipped++;
            integrity.onCorruptRegion(sourceName, damageStart, skipped, damageReason);
            buffer.position(limit);
        }

        return new DecodeStats(entries, corruptEntriesSkipped, bytesSkipped, missingEntries, undeliveredBytes, lastSequence);
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

    /**
     * Decodes a stored body checksum field.
     * <p>
     * A value that is neither the sentinel nor a possible CRC32C is a damaged payload, and
     * is treated as one: the throw lands in {@code decode}'s undecodable-payload branch,
     * which skips the entry and reports it, exactly as an out-of-range journal level does
     * in {@link #level(int)}. The two alternatives both fail: mapping it to
     * {@code NOT_RECORDED} skips verification on precisely the record that already looks
     * wrong, and taking it at face value stores a number no writer could have produced.
     */
    private static BodyChecksum storedChecksum(final long storedValue)
    {
        if (storedValue == R7fConstants.CHECKSUM_ABSENT)
        {
            return BodyChecksum.NOT_RECORDED;
        }
        try
        {
            return BodyChecksum.ofUnsigned32(storedValue);
        }
        catch (final IllegalArgumentException e)
        {
            throw new CorruptEntryException("body checksum field out of range: " + storedValue);
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
                final HeaderDelta delta = ev.headerDelta();
                final GatewayHeaders headers = delta == null ? new FbsUpstreamRequestHeaders(ev) : null;
                listener.onUpstreamRequest(reqId, level, startLine, headers, delta);
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
                final HeaderDelta delta = ev.headerDelta();
                final GatewayHeaders headers = delta == null ? new FbsClientResponseHeaders(ev) : null;
                listener.onClientResponse(reqId, level, startLine, headers, delta);
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

                // The other place the sentinel exists; see R7fJournal.endExchange. From here
                // on the distinction is carried by the type rather than by a value a caller
                // has to remember not to compare against.
                listener.onEnd(reqId, attributes, clientStartTs, clientEndTs, httpStatus, requestHeaderBytes, requestBodyBytes, responseHeaderBytes, responseBodyBytes, proxyStartTs, proxyFirstByteReceivedTs, proxyEnd,
                        storedChecksum(end.requestCrc32c()), storedChecksum(end.responseCrc32c()));
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

    /**
     * Marks a throw that came out of the consumer rather than out of this decoder.
     */
    static final class ListenerFailureException extends RuntimeException
    {
        ListenerFailureException(final Throwable cause)
        {
            // No stack trace of its own: this carries a cause and nothing else, and the
            // cause has the trace that matters.
            super(null, cause, false, false);
        }
    }

    /**
     * Wraps a consumer so that anything it throws is identifiable as its own.
     * <p>
     * Without the wrapper the decoder sees one {@code RuntimeException} out of
     * {@code dispatch} and cannot tell a payload it failed to decode from a sink that was
     * briefly unavailable — and the two want opposite handling. An undecodable entry has to
     * be skipped, because no later attempt would do better. A refused entry must not be,
     * because skipping it lets the caller checkpoint past a record it never received.
     * <p>
     * Seven methods of delegation buys that distinction. There is no cheaper seam: the
     * listener is called from inside {@code dispatch}, in the middle of the lazy FlatBuffers
     * field access that is the other thing that can throw there.
     */
    private record DeliveryGuard(JournalEventListener delegate) implements JournalEventListener
    {
        @Override
        public void onClientRequest(final String reqId, final JournalLevel level, final String startLine, final GatewayHeaders headers, final InetAddress remoteAddress, final IpSource ipSource)
        {
            try
            {
                delegate.onClientRequest(reqId, level, startLine, headers, remoteAddress, ipSource);
            }
            catch (final RuntimeException e)
            {
                throw new ListenerFailureException(e);
            }
        }

        @Override
        public void onUpstreamRequest(final String reqId, final JournalLevel level, final String startLine, final GatewayHeaders headers, final HeaderDelta delta)
        {
            try
            {
                delegate.onUpstreamRequest(reqId, level, startLine, headers, delta);
            }
            catch (final RuntimeException e)
            {
                throw new ListenerFailureException(e);
            }
        }

        @Override
        public void onRequestBody(final String reqId, final ByteBuffer bodyChunk)
        {
            try
            {
                delegate.onRequestBody(reqId, bodyChunk);
            }
            catch (final RuntimeException e)
            {
                throw new ListenerFailureException(e);
            }
        }

        @Override
        public void onResponseBody(final String reqId, final ByteBuffer bodyChunk)
        {
            try
            {
                delegate.onResponseBody(reqId, bodyChunk);
            }
            catch (final RuntimeException e)
            {
                throw new ListenerFailureException(e);
            }
        }

        @Override
        public void onUpstreamResponse(final String reqId, final JournalLevel level, final String startLine, final GatewayHeaders headers)
        {
            try
            {
                delegate.onUpstreamResponse(reqId, level, startLine, headers);
            }
            catch (final RuntimeException e)
            {
                throw new ListenerFailureException(e);
            }
        }

        @Override
        public void onClientResponse(final String reqId, final JournalLevel level, final String startLine, final GatewayHeaders headers, final HeaderDelta delta)
        {
            try
            {
                delegate.onClientResponse(reqId, level, startLine, headers, delta);
            }
            catch (final RuntimeException e)
            {
                throw new ListenerFailureException(e);
            }
        }

        @Override
        public void onEnd(final String reqId, final GatewayAttributes attributes,
                          final long clientStartTs, final long clientEndTs,
                          final int status,
                          final long requestHeaderBytes, final long requestBodyBytes, final long responseHeaderBytes, final long responseBodyBytes,
                          final long proxyStartTs, final long proxyFirstByteReceivedTs, final long proxyEndTs,
                          final BodyChecksum requestChecksum, final BodyChecksum responseChecksum)
        {
            try
            {
                delegate.onEnd(reqId, attributes, clientStartTs, clientEndTs, status,
                        requestHeaderBytes, requestBodyBytes, responseHeaderBytes, responseBodyBytes,
                        proxyStartTs, proxyFirstByteReceivedTs, proxyEndTs, requestChecksum, responseChecksum);
            }
            catch (final RuntimeException e)
            {
                throw new ListenerFailureException(e);
            }
        }
    }
}
