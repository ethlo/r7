package com.ethlo.r7.r7f;

import static java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.zip.CRC32C;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.api.GatewayAttributes;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.Journal;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.r7f.fbs.ClientRequest;
import com.ethlo.r7.r7f.fbs.DeltaBase;
import com.ethlo.r7.r7f.fbs.DeltaOp;
import com.ethlo.r7.r7f.fbs.DeltaOpKind;
import com.ethlo.r7.r7f.fbs.HeaderDelta;
import com.ethlo.r7.r7f.fbs.ClientResponse;
import com.ethlo.r7.r7f.fbs.EndExchange;
import com.ethlo.r7.r7f.fbs.EventPayload;
import com.ethlo.r7.r7f.fbs.FbsJournalLevel;
import com.ethlo.r7.r7f.fbs.Header;
import com.ethlo.r7.r7f.fbs.JournalEvent;
import com.ethlo.r7.r7f.fbs.RequestBody;
import com.ethlo.r7.r7f.fbs.ResponseBody;
import com.ethlo.r7.r7f.fbs.UpstreamRequest;
import com.ethlo.r7.r7f.fbs.UpstreamResponse;
import com.ethlo.r7.util.IndexedGatewayHeaders;
import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.ZstdCompressCtx;
import com.google.flatbuffers.FlatBufferBuilder;

public final class R7fJournal implements Journal
{
    private static final Logger logger = LoggerFactory.getLogger(R7fJournal.class);
    private static final ValueLayout.OfInt INT_BE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfShort SHORT_BE = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong LONG_BE = JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    /**
     * Initial size of the reusable ASCII scratch buffer. Sized for the common case; it
     * grows on demand rather than throwing, because dropping an audit record because a
     * cookie was large is the wrong trade.
     */
    private static final int INITIAL_SCRATCH = 8192;

    /**
     * Hard ceiling for a single string (header name, header value or start line). Beyond
     * this we refuse rather than let one pathological request drive an unbounded
     * allocation.
     */
    private static final int MAX_SCRATCH = 1 << 20;

    /**
     * How long close() waits for each rotation finalizer. Matches the provider's own
     * shutdown budget.
     */
    private static final long FINALIZER_SHUTDOWN_TIMEOUT_MILLIS = 5_000L;

    private static final int INITIAL_HEADER_SLOTS = 1024;
    private static final int INITIAL_ATTRIBUTE_SLOTS = 128;

    /**
     * Per-thread encoding state.
     * <p>
     * Everything an entry needs before it reaches the segment — the FlatBuffer builder, the
     * latin-1 scratch, the offset arrays and the CRC — lives here rather than on the journal,
     * because that is what allows encoding to happen outside {@link #writeEntry}'s monitor.
     * Held as a single object so the traversal callbacks can carry it as their state and stay
     * free of captures.
     * <p>
     * A {@link ThreadLocal} is appropriate because journal writes come from the IO threads and
     * the exchange completion listeners that run on them — a bounded set — which is the same
     * assumption {@code StartLineBuilder} and {@code RedactUtil} already make on this path. A
     * write arriving on a short-lived virtual thread costs that thread its own encoder rather
     * than correctness.
     */
    private final ThreadLocal<EntryEncoder> encoders = ThreadLocal.withInitial(EntryEncoder::new);

    private final R7fJournalProvider provider;

    /**
     * Bumped after every commit and rotation, so a tailer can wait for news without polling
     * the segment (design/live-tailing.md).
     */
    private final CommitSignal commitSignal;
    private final Consumer<Path> finishedJournalFileSupplier;

    private static final byte[] FBS_JOURNAL_LEVELS = new byte[]{
            FbsJournalLevel.NONE,
            FbsJournalLevel.METADATA,
            FbsJournalLevel.HEADERS,
            FbsJournalLevel.FULL,
    };

    /**
     * The block size this journal writes, recorded in every segment's preamble.
     */
    private static final int BLOCK_SIZE = R7fConstants.DEFAULT_BLOCK_SIZE;

    /**
     * zstd level (FORMAT.md 4.4), or 0 for none: the provider's, which has already fallen back
     * to 0 where zstd cannot be loaded.
     */
    private final int compressionLevel;
    private final short codec;

    // Compression state, all of it guarded by this journal's monitor
    private ZstdCompressCtx stream;
    private ZstdCompressCtx single;
    /** The block whose stream {@link #stream} is in, or -1 when the next stream entry starts one. */
    private long streamBlock = -1;
    private ByteBuffer packed;
    private MemorySegment packedSegment;

    private MemorySegment segment;
    /**
     * A buffer view of {@link #segment}, for computing fragment CRCs over what was just
     * written without allocating a view per fragment. Only touched under the monitor.
     */
    private ByteBuffer segmentView;
    private Arena arena;
    private Path activePath;
    private long position;
    /**
     * Rotation finalizers still running. Registered before they start, so close() cannot
     * race past one, and each removes itself when it is done, so this stays empty in steady
     * state rather than accumulating dead threads for the life of the journal.
     */
    private final Set<Thread> pendingFinalizers = ConcurrentHashMap.newKeySet();

    /**
     * The first failure a rotation finalizer reported, surfaced by close(). A segment that
     * could not be sealed is a fact about the shutdown, and a close() that returns normally
     * has said the opposite.
     */
    private volatile IOException finalizerFailure;

    private boolean closed;

    /**
     * Keeps the active segment's next few MB faulted in, so the writer does not take page
     * faults while holding this journal's monitor. Null where the platform cannot, or the
     * segments are pre-faulted anyway.
     */
    private final FaultAhead faultAhead;

    /**
     * Sequence number of the next entry written to the current segment. Reset on every
     * rotation; persisted per entry so that a reader can tell missing data from
     * end-of-data.
     */
    private int nextSequence = R7fConstants.FIRST_ENTRY_SEQUENCE;

    /**
     * Wall-clock creation time of the active segment. Used for the file name, where it
     * has to mean something to a human and has to survive a restart — which rules out
     * {@link System#nanoTime()}, whose origin is arbitrary and per-JVM.
     */
    private long segmentStartEpochMillis;

    /**
     * Monotonic companion to {@link #segmentStartEpochMillis}, used for measuring segment
     * lifetime without exposure to wall-clock steps (NTP, VM migration, manual changes).
     */
    private long segmentStartNanos;

    public R7fJournal(final R7fJournalProvider provider, final Consumer<Path> finishedJournalFileSupplier)
    {
        this.provider = provider;
        this.finishedJournalFileSupplier = finishedJournalFileSupplier;
        this.faultAhead = FaultAhead.create(provider.isPreFault());
        this.compressionLevel = provider.getCompressionLevel();
        this.codec = compressionLevel > 0 ? R7fConstants.CODEC_ZSTD : R7fConstants.CODEC_NONE;
        this.commitSignal = provider.openCommitSignal();
        rotateSegment();
    }

    public R7fJournal(final R7fJournalProvider provider)
    {
        this(provider, _ -> {
                }
        );
    }

    @Override
    public int clientRequest(JournalLevel level, String reqId, ByteBuffer startLine, GatewayHeaders headers, final InetAddress inetAddress, IpSource ipSource)
    {
        final EntryEncoder enc = encoders.get();
        final FlatBufferBuilder fbb = enc.fbb;
        fbb.clear();
        int reqIdOff = fbb.createByteVector(enc.asciiScratch, 0, enc.copyToScratch(reqId));
        int lineOff = fbb.createByteVector(startLine);
        int headOff = enc.buildHeadersVector(headers);
        int remoteAddressOff = fbb.createByteVector(inetAddress.getAddress());

        ClientRequest.startClientRequest(fbb);
        ClientRequest.addJournalLevel(fbb, FBS_JOURNAL_LEVELS[level.ordinal()]);
        ClientRequest.addReqId(fbb, reqIdOff);
        ClientRequest.addStartLine(fbb, lineOff);
        ClientRequest.addHeaders(fbb, headOff);
        ClientRequest.addClientIp(fbb, remoteAddressOff);
        ClientRequest.addClientIpSource(fbb, ipSource.byteValue());
        return finishAndWrite(enc, EventPayload.ClientRequest, ClientRequest.endClientRequest(fbb));
    }

    @Override
    public int upstreamRequest(JournalLevel level, String reqId, ByteBuffer startLine, GatewayHeaders headers, GatewayHeaders base)
    {
        final EntryEncoder enc = encoders.get();
        final FlatBufferBuilder fbb = enc.fbb;
        fbb.clear();
        int reqIdOff = fbb.createByteVector(enc.asciiScratch, 0, enc.copyToScratch(reqId));
        int lineOff = fbb.createByteVector(startLine);

        // Exactly one of the two: a difference when there is a base to express it against, and
        // the whole set otherwise. Nothing downstream has to guess which, because the field it
        // finds says so.
        final int deltaOff = enc.buildHeaderDelta(DeltaBase.CLIENT_REQUEST, base, headers);
        final int headOff = deltaOff == 0 ? enc.buildHeadersVector(headers) : 0;

        UpstreamRequest.startUpstreamRequest(fbb);
        UpstreamRequest.addJournalLevel(fbb, FBS_JOURNAL_LEVELS[level.ordinal()]);
        UpstreamRequest.addReqId(fbb, reqIdOff);
        UpstreamRequest.addStartLine(fbb, lineOff);
        if (deltaOff == 0)
        {
            UpstreamRequest.addHeaders(fbb, headOff);
        }
        else
        {
            UpstreamRequest.addHeaderDelta(fbb, deltaOff);
        }
        return finishAndWrite(enc, EventPayload.UpstreamRequest, UpstreamRequest.endUpstreamRequest(fbb));
    }

    @Override
    public int upstreamResponse(JournalLevel level, String reqId, int statusCode, ByteBuffer startLine, GatewayHeaders headers)
    {
        final EntryEncoder enc = encoders.get();
        final FlatBufferBuilder fbb = enc.fbb;
        fbb.clear();
        int reqIdOff = fbb.createByteVector(enc.asciiScratch, 0, enc.copyToScratch(reqId));
        int lineOff = fbb.createByteVector(startLine);
        int headOff = enc.buildHeadersVector(headers);

        UpstreamResponse.startUpstreamResponse(fbb);
        UpstreamResponse.addJournalLevel(fbb, FBS_JOURNAL_LEVELS[level.ordinal()]);
        UpstreamResponse.addReqId(fbb, reqIdOff);
        UpstreamResponse.addStatus(fbb, statusCode);
        UpstreamResponse.addStartLine(fbb, lineOff);
        UpstreamResponse.addHeaders(fbb, headOff);
        return finishAndWrite(enc, EventPayload.UpstreamResponse, UpstreamResponse.endUpstreamResponse(fbb));
    }

    @Override
    public int clientResponse(JournalLevel level, String reqId, int statusCode, ByteBuffer startLine, GatewayHeaders headers, GatewayHeaders base)
    {
        final EntryEncoder enc = encoders.get();
        final FlatBufferBuilder fbb = enc.fbb;
        fbb.clear();
        int reqIdOff = fbb.createByteVector(enc.asciiScratch, 0, enc.copyToScratch(reqId));
        int lineOff = fbb.createByteVector(startLine);

        final int deltaOff = enc.buildHeaderDelta(DeltaBase.UPSTREAM_RESPONSE, base, headers);
        final int headOff = deltaOff == 0 ? enc.buildHeadersVector(headers) : 0;

        ClientResponse.startClientResponse(fbb);
        ClientResponse.addJournalLevel(fbb, FBS_JOURNAL_LEVELS[level.ordinal()]);
        ClientResponse.addReqId(fbb, reqIdOff);
        ClientResponse.addStatus(fbb, statusCode);
        ClientResponse.addStartLine(fbb, lineOff);
        if (deltaOff == 0)
        {
            ClientResponse.addHeaders(fbb, headOff);
        }
        else
        {
            ClientResponse.addHeaderDelta(fbb, deltaOff);
        }
        return finishAndWrite(enc, EventPayload.ClientResponse, ClientResponse.endClientResponse(fbb));
    }

    /* ============================================================
       BODY DATA CHANNELS
       ============================================================ */

    @Override
    public int requestBody(String reqId, ByteBuffer data)
    {
        if (!data.hasRemaining())
        {
            throw new IllegalStateException("No data available for request body");
        }
        final EntryEncoder enc = encoders.get();
        final FlatBufferBuilder fbb = enc.fbb;
        fbb.clear();
        int reqIdOff = fbb.createByteVector(enc.asciiScratch, 0, enc.copyToScratch(reqId));
        RequestBody.startRequestBody(fbb);
        RequestBody.addReqId(fbb, reqIdOff);
        RequestBody.addLength(fbb, data.remaining());
        return finishAndWrite(enc, EventPayload.RequestBody, RequestBody.endRequestBody(fbb), data);
    }

    @Override
    public int responseBody(String reqId, ByteBuffer data)
    {
        if (!data.hasRemaining())
        {
            throw new IllegalStateException("No data available for response body");
        }
        final EntryEncoder enc = encoders.get();
        final FlatBufferBuilder fbb = enc.fbb;
        fbb.clear();
        int reqIdOff = fbb.createByteVector(enc.asciiScratch, 0, enc.copyToScratch(reqId));
        ResponseBody.startResponseBody(fbb);
        ResponseBody.addReqId(fbb, reqIdOff);
        ResponseBody.addLength(fbb, data.remaining());
        return finishAndWrite(enc, EventPayload.ResponseBody, ResponseBody.endResponseBody(fbb), data);
    }

    @Override
    public int endExchange(String reqId, GatewayAttributes attributes, final long requestStartTs, final long requestEndTs, int statusCode, long requestHeaderBytes, long requestBodyBytes, long responseHeaderBytes, long responseBodyBytes, final long proxyStartTs, final long proxyFirstByteReceivedTs, final long proxyEndTs, final BodyChecksum requestChecksum, final BodyChecksum responseChecksum)
    {
        final EntryEncoder enc = encoders.get();
        final FlatBufferBuilder fbb = enc.fbb;
        fbb.clear();
        int reqIdOff = fbb.createByteVector(enc.asciiScratch, 0, enc.copyToScratch(reqId));
        int attrVecOff = enc.buildAttributesVector(attributes);

        EndExchange.startEndExchange(fbb);
        EndExchange.addReqId(fbb, reqIdOff);
        EndExchange.addClientStart(fbb, requestStartTs);
        EndExchange.addStatus(fbb, statusCode);
        EndExchange.addRequestHeaderBytes(fbb, requestHeaderBytes);
        EndExchange.addRequestBodyBytes(fbb, requestBodyBytes);
        EndExchange.addResponseHeaderBytes(fbb, responseHeaderBytes);
        EndExchange.addResponseBodyBytes(fbb, responseBodyBytes);
        EndExchange.addClientEnd(fbb, requestEndTs);
        EndExchange.addProxyStart(fbb, proxyStartTs);
        EndExchange.addProxyFirstByteReceived(fbb, proxyFirstByteReceivedTs);
        EndExchange.addProxyEnd(fbb, proxyEndTs);
        EndExchange.addAttributes(fbb, attrVecOff);
        // One of the two places the sentinel exists. The format has no types, so the
        // distinction BodyChecksum carries has to be encoded here and decoded by
        // JournalDecoder; nothing in between ever sees the number.
        EndExchange.addRequestCrc32c(fbb, stored(requestChecksum));
        EndExchange.addResponseCrc32c(fbb, stored(responseChecksum));
        return finishAndWrite(enc, EventPayload.EndExchange, EndExchange.endEndExchange(fbb));
    }

    /**
     * Encodes a body checksum for storage. The counterpart is {@code JournalDecoder}'s
     * decode of the same field; the two are kept honest by
     * {@code checksumsRoundTripThroughTheJournal}.
     */
    private static long stored(final BodyChecksum checksum)
    {
        return checksum.isRecorded() ? checksum.value() : R7fConstants.CHECKSUM_ABSENT;
    }

    /* ============================================================
       PRIVATE LOGIC & UTILITIES
       ============================================================ */

    private int finishAndWrite(final EntryEncoder enc, byte type, int offset)
    {
        return finishAndWrite(enc, type, offset, null);
    }

    /**
     * Completes the encoder's buffer and hands it to {@link #writeEntry}.
     * <p>
     * Everything above this line — the FlatBuffer build, the latin-1 copying and, since
     * redaction became a view, the fingerprinting it triggers — runs on the calling thread
     * with no lock held. Only the segment itself is shared, and only {@code writeEntry}
     * touches it.
     */
    private int finishAndWrite(final EntryEncoder enc, byte type, int offset, ByteBuffer rawData)
    {
        final FlatBufferBuilder fbb = enc.fbb;
        JournalEvent.startJournalEvent(fbb);
        JournalEvent.addEventType(fbb, type);
        JournalEvent.addEvent(fbb, offset);
        fbb.finish(JournalEvent.endJournalEvent(fbb));
        if (compressionLevel > 0)
        {
            // The copy into one contiguous buffer happens outside the monitor; compression
            // cannot, because the block's stream is shared and its order is the sequence order.
            enc.fillPlain(fbb.dataBuffer(), rawData);
            return writeCompressed(enc, rawData);
        }
        return writeEntry(enc, rawData);
    }

    /**
     * Claims this entry's place in the segment and publishes it.
     * <p>
     * Synchronized because {@link #position}, {@link #nextSequence} and the segment itself are
     * the journal's, not the caller's: the sequence must be contiguous within a segment and
     * the bytes must land where the sequence says they do, so claiming and copying cannot be
     * separated without a different publication protocol. The encoder it reads from is
     * thread-confined, so no other thread can be building into it while this runs.
     * <p>
     * The entry's content is {@code sequence fbLen rawLen fb raw}, stored as one FULL fragment
     * when it fits the rest of the current block and split across blocks otherwise
     * (FORMAT.md 4.3). No fragment crosses a block boundary; that is what lets a reader resume
     * at the next boundary after damage without ever reading payload as framing.
     */
    private synchronized int writeEntry(final EntryEncoder enc, ByteBuffer rawData)
    {
        final ByteBuffer fbBuf = enc.fbb.dataBuffer();
        final int fbLen = fbBuf.remaining();
        final int rawLen = (rawData != null) ? rawData.remaining() : 0;
        final long contentLength = R7fConstants.ENTRY_CONTENT_HEADER_SIZE + (long) fbLen + rawLen;

        ensureCapacity(contentLength);

        // After ensureCapacity, which may have rotated to a new segment
        final long claimedFrom = position;
        final long start = R7fFraming.entryStart(position, BLOCK_SIZE);
        final int sequence = nextSequence;

        enc.prepareSources(sequence, fbBuf, fbLen, rawData, rawLen);

        final long firstCapacity = R7fFraming.firstCapacity(start, BLOCK_SIZE);
        final long end;
        if (contentLength <= firstCapacity)
        {
            writeFragmentBody(enc, start, R7fConstants.FRAGMENT_FULL, 0, contentLength);
            end = start + R7fConstants.FRAGMENT_HEADER_SIZE + contentLength;
        }
        else
        {
            // Continuations first, complete with their magics, and the FIRST fragment last of
            // all. A reader stops at the FIRST fragment's zero magic and so never reaches the
            // continuations until the entry is whole; once it sees that magic, everything
            // behind it is already in place (FORMAT.md 5.1).
            final int perBlock = R7fFraming.continuationCapacity(BLOCK_SIZE);
            long logical = firstCapacity;
            long at = R7fFraming.nextBoundary(start, BLOCK_SIZE);
            long last = at;
            while (logical < contentLength)
            {
                final long length = Math.min(perBlock, contentLength - logical);
                final byte type = logical + length == contentLength ? R7fConstants.FRAGMENT_LAST : R7fConstants.FRAGMENT_MIDDLE;
                writeFragmentBody(enc, at, type, logical, length);
                segment.set(INT_BE, at, R7fConstants.FRAGMENT_MAGIC);
                logical += length;
                last = at + R7fConstants.FRAGMENT_HEADER_SIZE + length;
                at += BLOCK_SIZE;
            }
            writeFragmentBody(enc, start, R7fConstants.FRAGMENT_FIRST, 0, firstCapacity);
            end = last;
        }

        // Publish. The fence keeps every store above from being reordered after the magic,
        // so a reader — in this process or, as in production, in the tailer process sharing
        // this mapping — never sees a magic without the entry behind it. The slot is zero
        // until now, because segments are pre-allocated zero-filled and never reused, and a
        // zero where a magic belongs is already the reader's end-of-data signal.
        VarHandle.releaseFence();
        segment.set(INT_BE, start, R7fConstants.FRAGMENT_MAGIC);

        if (rawLen > 0)
        {
            rawData.position(rawData.position() + rawLen);
        }
        enc.releaseSources();

        position = end;
        nextSequence++;
        commitSignal.signal();

        if (faultAhead != null)
        {
            faultAhead.advance(segment, position);
        }

        return (int) (end - claimedFrom);
    }

    /**
     * {@link #writeEntry} for {@link R7fConstants#CODEC_ZSTD}.
     * <p>
     * Compression happens under the monitor, because the block's stream is shared and its order
     * is the sequence order. Each entry is flushed, so its bytes decode without waiting for the
     * next one, and it is committed by its own magic like any other entry.
     */
    private synchronized int writeCompressed(final EntryEncoder enc, final ByteBuffer rawData)
    {
        if (closed)
        {
            throw new IllegalStateException("Journal is closed");
        }
        if (segment == null)
        {
            rotateSegment();
        }
        final int plainLength = enc.plainLength;

        final long perBlock = R7fFraming.continuationCapacity(BLOCK_SIZE);
        final long bound = compressBound(plainLength) + 64;
        ensurePacked(Math.max(bound + Integer.BYTES, 2L * BLOCK_SIZE));
        final long claimedFrom = position;
        final long publishAt;

        if (bound <= perBlock)
        {
            long start = R7fFraming.entryStart(position, BLOCK_SIZE);
            if (R7fFraming.firstCapacity(start, BLOCK_SIZE) < bound)
            {
                // The worst case might not fit what is left of the block, and a stream cannot
                // take an entry back once it has been fed. Close the block off.
                writePad(start);
                start = R7fFraming.nextBoundary(start, BLOCK_SIZE);
            }
            if (start + R7fConstants.FRAGMENT_HEADER_SIZE + bound > segment.byteSize())
            {
                rotateSegment();
                start = R7fFraming.entryStart(position, BLOCK_SIZE);
            }

            // Only now: placing the entry may have rotated, and rotation restarts the sequence
            enc.plain.putInt(0, nextSequence);
            final long block = start / BLOCK_SIZE;
            final byte flags;
            if (block != streamBlock)
            {
                if (stream == null)
                {
                    stream = new ZstdCompressCtx();
                }
                stream.reset();
                stream.setLevel(compressionLevel).setChecksum(false).setContentSize(false);
                streamBlock = block;
                flags = R7fConstants.FLAG_STREAM_START;
            }
            else
            {
                flags = R7fConstants.FLAG_STREAM_CONTINUE;
            }

            packed.clear();
            enc.plain.limit(plainLength).position(0);
            while (!stream.compressDirectByteBufferStream(packed, enc.plain, EndDirective.FLUSH))
            {
                // FLUSH returns true once everything fed so far is out
            }
            final int length = packed.position();
            if (length > R7fFraming.firstCapacity(start, BLOCK_SIZE))
            {
                // Cannot happen while the bound holds. If it ever did, writing it would put a
                // fragment across a block boundary, which every reader relies on never seeing.
                throw new IllegalStateException("Compressed entry of " + length + " bytes exceeds its bound of " + bound);
            }
            enc.preparePackedSources(packedSegment, length);
            writeFragmentBody(enc, start, R7fConstants.FRAGMENT_FULL, flags, 0, length);
            publishAt = start;
            position = start + R7fConstants.FRAGMENT_HEADER_SIZE + length;
        }
        else
        {
            if (single == null)
            {
                single = new ZstdCompressCtx().setLevel(compressionLevel).setChecksum(false);
            }
            // Rotate on the worst case first, so the sequence stamped below is this segment's
            ensureCapacity(Integer.BYTES + bound);
            enc.plain.putInt(0, nextSequence);
            final int frame = single.compressDirectByteBuffer(packed, Integer.BYTES, packed.capacity() - Integer.BYTES,
                    enc.plain, 0, plainLength);
            packed.putInt(0, plainLength);
            final long contentLength = Integer.BYTES + (long) frame;
            enc.preparePackedSources(packedSegment, contentLength);
            publishAt = writeFragments(enc, contentLength, R7fConstants.FLAG_STANDALONE);
            streamBlock = -1;
        }

        VarHandle.releaseFence();
        segment.set(INT_BE, publishAt, R7fConstants.FRAGMENT_MAGIC);

        if (rawData != null && rawData.hasRemaining())
        {
            rawData.position(rawData.limit());
        }
        enc.releaseSources();
        nextSequence++;
        commitSignal.signal();
        if (faultAhead != null)
        {
            faultAhead.advance(segment, position);
        }
        return (int) (position - claimedFrom);
    }

    /**
     * zstd's {@code ZSTD_COMPRESSBOUND}: the most a single-shot compression of
     * {@code plainLength} bytes can produce. Computed here rather than by
     * {@code Zstd.compressBound}, which is a JNI transition per entry made while holding this
     * journal's monitor, for what is a line of arithmetic. {@code compressBoundMatchesZstd}
     * keeps the two equal.
     */
    static long compressBound(final int plainLength)
    {
        final int smallInput = 128 << 10;
        return plainLength + (plainLength >>> 8) + (plainLength < smallInput ? (smallInput - plainLength) >>> 11 : 0);
    }

    /**
     * Places the content the encoder's sources describe from {@link #position}, every fragment
     * but the commit magic, and returns where that magic belongs.
     */
    private long writeFragments(final EntryEncoder enc, final long contentLength, final byte flags)
    {
        final long start = R7fFraming.entryStart(position, BLOCK_SIZE);
        final long firstCapacity = R7fFraming.firstCapacity(start, BLOCK_SIZE);
        if (contentLength <= firstCapacity)
        {
            writeFragmentBody(enc, start, R7fConstants.FRAGMENT_FULL, flags, 0, contentLength);
            position = start + R7fConstants.FRAGMENT_HEADER_SIZE + contentLength;
            return start;
        }
        final int perBlock = R7fFraming.continuationCapacity(BLOCK_SIZE);
        long logical = firstCapacity;
        long at = R7fFraming.nextBoundary(start, BLOCK_SIZE);
        long last = at;
        while (logical < contentLength)
        {
            final long length = Math.min(perBlock, contentLength - logical);
            final byte type = logical + length == contentLength ? R7fConstants.FRAGMENT_LAST : R7fConstants.FRAGMENT_MIDDLE;
            writeFragmentBody(enc, at, type, flags, logical, length);
            segment.set(INT_BE, at, R7fConstants.FRAGMENT_MAGIC);
            logical += length;
            last = at + R7fConstants.FRAGMENT_HEADER_SIZE + length;
            at += BLOCK_SIZE;
        }
        writeFragmentBody(enc, start, R7fConstants.FRAGMENT_FIRST, flags, 0, firstCapacity);
        position = last;
        return start;
    }

    /**
     * A committed fragment that is not an entry, filling the rest of the block from
     * {@code start}. Its data is the segment's own zero fill.
     */
    private void writePad(final long start)
    {
        final long length = R7fFraming.firstCapacity(start, BLOCK_SIZE);
        segment.set(ValueLayout.JAVA_BYTE, start + R7fConstants.FRAGMENT_OFF_TYPE, R7fConstants.FRAGMENT_FULL);
        segment.set(ValueLayout.JAVA_BYTE, start + R7fConstants.FRAGMENT_OFF_FLAGS, R7fConstants.FLAG_PAD);
        segment.set(INT_BE, start + R7fConstants.FRAGMENT_OFF_LENGTH, (int) length);
        final CRC32C crc = new CRC32C();
        final long dataAt = start + R7fConstants.FRAGMENT_HEADER_SIZE;
        crc.update(segmentView.limit((int) (start + R7fConstants.FRAGMENT_OFF_CRC)).position((int) (start + R7fConstants.FRAGMENT_OFF_TYPE)));
        segmentView.clear();
        crc.update(segmentView.limit((int) (dataAt + length)).position((int) dataAt));
        segmentView.clear();
        segment.set(INT_BE, start + R7fConstants.FRAGMENT_OFF_CRC, (int) crc.getValue());
        VarHandle.releaseFence();
        segment.set(INT_BE, start, R7fConstants.FRAGMENT_MAGIC);
        position = start + R7fConstants.FRAGMENT_HEADER_SIZE + length;
    }

    private void ensurePacked(final long capacity)
    {
        if (packed == null || packed.capacity() < capacity)
        {
            packed = ByteBuffer.allocateDirect((int) capacity);
            packedSegment = MemorySegment.ofBuffer(packed);
        }
    }

    /**
     * Writes everything of a fragment but its magic: the header fields, the data (bytes
     * {@code [from, from + length)} of the entry's content) and the CRC over both.
     * <p>
     * The CRC is taken from the segment after the copy rather than from the sources: the
     * content is three separate buffers, and the copy has just brought these bytes into cache.
     */
    private void writeFragmentBody(final EntryEncoder enc, final long at, final byte type, final long from, final long length)
    {
        writeFragmentBody(enc, at, type, (byte) 0, from, length);
    }

    private void writeFragmentBody(final EntryEncoder enc, final long at, final byte type, final byte flags, final long from, final long length)
    {
        segment.set(ValueLayout.JAVA_BYTE, at + R7fConstants.FRAGMENT_OFF_TYPE, type);
        segment.set(ValueLayout.JAVA_BYTE, at + R7fConstants.FRAGMENT_OFF_FLAGS, flags);
        segment.set(INT_BE, at + R7fConstants.FRAGMENT_OFF_LENGTH, (int) length);
        final long dataAt = at + R7fConstants.FRAGMENT_HEADER_SIZE;
        enc.copyContent(segment, from, length, dataAt);

        final CRC32C crc = enc.crc;
        crc.reset();
        crc.update(segmentView.limit((int) (at + R7fConstants.FRAGMENT_OFF_CRC)).position((int) (at + R7fConstants.FRAGMENT_OFF_TYPE)));
        segmentView.clear();
        crc.update(segmentView.limit((int) (dataAt + length)).position((int) dataAt));
        segmentView.clear();
        segment.set(INT_BE, at + R7fConstants.FRAGMENT_OFF_CRC, (int) crc.getValue());
    }

    /**
     * One thread's encoding workspace. Every method here runs outside the journal's monitor,
     * so nothing it touches may be shared between threads.
     */
    private static final class EntryEncoder
    {
        private final FlatBufferBuilder fbb = new FlatBufferBuilder(8192);
        private final CRC32C crc = new CRC32C();

        private byte[] asciiScratch = new byte[INITIAL_SCRATCH];
        private int[] headerOffsetsScratch = new int[INITIAL_HEADER_SLOTS];
        private int[] attributeOffsetsScratch = new int[INITIAL_ATTRIBUTE_SLOTS];

        private int currentHeaderCount;
        private int currentAttributeCount;

        /** Reused across exchanges; a diff needs the base indexable and the ops somewhere. */
        private final HeaderDeltaCodec.Ops deltaOps = new HeaderDeltaCodec.Ops();
        private int[] deltaOpOffsets = new int[32];

        /**
         * Encodes {@code target} as its difference from {@code base}.
         *
         * @return the offset of the HeaderDelta table, or 0 when there is no base to express a
         *         difference against, in which case the caller writes the full set instead
         */
        private int buildHeaderDelta(final byte baseKind, final GatewayHeaders base, final GatewayHeaders target)
        {
            if (base == null)
            {
                return 0;
            }
            final IndexedGatewayHeaders indexedBase = HeaderDeltaCodec.materialise(base);
            if (indexedBase.size() == 0)
            {
                // Nothing to refer to. A delta against an empty base is the full set with extra
                // framing, and worse, it would make the record depend on an entry that carries
                // nothing.
                return 0;
            }

            HeaderDeltaCodec.diff(indexedBase, target, deltaOps);

            final int opCount = deltaOps.size;
            if (deltaOpOffsets.length < opCount)
            {
                deltaOpOffsets = new int[Integer.highestOneBit(opCount) * 2];
            }

            for (int i = 0; i < opCount; i++)
            {
                if (deltaOps.kind[i] == HeaderDeltaCodec.Ops.COPY)
                {
                    DeltaOp.startDeltaOp(fbb);
                    DeltaOp.addKind(fbb, DeltaOpKind.COPY);
                    DeltaOp.addBaseIndex(fbb, deltaOps.baseIndex[i]);
                    DeltaOp.addCount(fbb, deltaOps.count[i]);
                    deltaOpOffsets[i] = DeltaOp.endDeltaOp(fbb);
                }
                else
                {
                    final int nameOff = fbb.createByteVector(asciiScratch, 0, copyToScratch(deltaOps.emittedName[i]));
                    final int valueOff = fbb.createByteVector(asciiScratch, 0, copyToScratch(deltaOps.emittedValue[i]));
                    DeltaOp.startDeltaOp(fbb);
                    DeltaOp.addKind(fbb, DeltaOpKind.EMIT);
                    DeltaOp.addName(fbb, nameOff);
                    DeltaOp.addValue(fbb, valueOff);
                    deltaOpOffsets[i] = DeltaOp.endDeltaOp(fbb);
                }
            }

            final int opsVector = createOffsetVector(deltaOpOffsets, opCount);
            HeaderDelta.startHeaderDelta(fbb);
            HeaderDelta.addBase(fbb, baseKind);
            HeaderDelta.addOps(fbb, opsVector);
            return HeaderDelta.endHeaderDelta(fbb);
        }

        private int buildHeadersVector(final GatewayHeaders headers)
        {
            this.currentHeaderCount = 0;
            if (headers instanceof final IndexedGatewayHeaders indexed)
            {
                // What StatefulJournal always passes: read by position, no callback per header.
                final int count = indexed.size();
                for (int i = 0; i < count; i++)
                {
                    headerOffsetsScratch = ensureSlot(headerOffsetsScratch, i);
                    headerWrite(indexed.name(i), indexed.value(i));
                    headerOffsetsScratch[i] = Header.endHeader(fbb);
                }
                return count == 0 ? 0 : createOffsetVector(headerOffsetsScratch, count);
            }
            headers.forEach(this, (self, name, value) ->
                    {
                        self.headerOffsetsScratch = ensureSlot(self.headerOffsetsScratch, self.currentHeaderCount);
                        self.headerWrite(name, value);
                        self.headerOffsetsScratch[self.currentHeaderCount++] = Header.endHeader(self.fbb);
                    }
            );
            return currentHeaderCount == 0 ? 0 : createOffsetVector(headerOffsetsScratch, currentHeaderCount);
        }

        private void headerWrite(final String name, final String value)
        {
            int nOff = fbb.createByteVector(asciiScratch, 0, copyToScratch(name));
            int vOff = fbb.createByteVector(asciiScratch, 0, copyToScratch(value));
            Header.startHeader(fbb);
            Header.addName(fbb, nOff);
            Header.addValue(fbb, vOff);
        }

        private int buildAttributesVector(final GatewayAttributes attributes)
        {
            this.currentAttributeCount = 0;
            if (attributes != null)
            {
                attributes.forEach(this, (self, name, value) -> {
                            self.attributeOffsetsScratch = ensureSlot(self.attributeOffsetsScratch, self.currentAttributeCount);
                            self.headerWrite(name, value);
                            self.attributeOffsetsScratch[self.currentAttributeCount++] = Header.endHeader(self.fbb);
                        }
                );
            }
            return currentAttributeCount == 0 ? 0 : createOffsetVector(attributeOffsetsScratch, currentAttributeCount);
        }

        /**
         * Grows an offset scratch array when a request carries more headers or attributes
         * than the current array holds. Doubling means this is amortised away after the first
         * few requests, and the steady state stays allocation-free.
         */
        private static int[] ensureSlot(final int[] current, final int index)
        {
            if (index < current.length)
            {
                return current;
            }
            final int[] grown = new int[current.length * 2];
            System.arraycopy(current, 0, grown, 0, current.length);
            return grown;
        }

        private int createOffsetVector(final int[] offsets, final int count)
        {
            fbb.startVector(4, count, 4);
            for (int i = count - 1; i >= 0; i--) fbb.addOffset(offsets[i]);
            return fbb.endVector();
        }

        /**
         * Copies the latin-1 bytes of the given string into the reusable scratch buffer.
         * <p>
         * The deprecated {@code String.getBytes(int, int, byte[], int)} is used deliberately:
         * it truncates each char to its low byte with no intermediate allocation, which is
         * exactly the ISO-8859-1 encoding HTTP header values arrive in. The decoder reads
         * them back as ISO-8859-1, so the bytes round-trip unchanged.
         */
        @SuppressWarnings("deprecation")
        private int copyToScratch(final String str)
        {
            final int len = str.length();
            if (len > MAX_SCRATCH)
            {
                throw new IllegalArgumentException("Journal value exceeds the maximum of " + MAX_SCRATCH + " bytes: " + len);
            }
            if (len > asciiScratch.length)
            {
                int capacity = asciiScratch.length;
                while (capacity < len)
                {
                    capacity <<= 1;
                }
                asciiScratch = new byte[capacity];
            }
            str.getBytes(0, len, asciiScratch, 0);
            return len;
        }

        /**
         * The entry's content is three sources laid end to end: this 12-byte head, the
         * FlatBuffer and the raw payload. Fragments cut that logical stream wherever block
         * boundaries fall, which can be inside any of the three.
         */
        private final MemorySegment head = MemorySegment.ofArray(new byte[R7fConstants.ENTRY_CONTENT_HEADER_SIZE]);
        private long headLength = R7fConstants.ENTRY_CONTENT_HEADER_SIZE;

        // Compression only: the whole content in one direct buffer, sequence left to fill in
        private ByteBuffer plain;
        private int plainLength;

        private void fillPlain(final ByteBuffer fbBuf, final ByteBuffer rawData)
        {
            final int fbLen = fbBuf.remaining();
            final int rawLen = rawData != null ? rawData.remaining() : 0;
            plainLength = R7fConstants.ENTRY_CONTENT_HEADER_SIZE + fbLen + rawLen;
            if (plain == null || plain.capacity() < plainLength)
            {
                plain = ByteBuffer.allocateDirect(Math.max(plainLength, 64 * 1024));
            }
            plain.clear();
            plain.putInt(0).putInt(fbLen).putInt(rawLen).put(fbBuf.duplicate());
            if (rawLen > 0)
            {
                plain.put(rawData.duplicate());
            }
        }

        /** The content is {@code length} bytes of {@code source}, with no head of its own. */
        private void preparePackedSources(final MemorySegment source, final long length)
        {
            headLength = 0;
            fbSource = source.asSlice(0, length);
            rawSource = null;
            fbEnd = length;
        }

        private MemorySegment fbSource;
        private MemorySegment rawSource;
        private long fbEnd;

        private void prepareSources(final int sequence, final ByteBuffer fbBuf, final int fbLen, final ByteBuffer rawData, final int rawLen)
        {
            head.set(INT_BE, 0, sequence);
            head.set(INT_BE, Integer.BYTES, fbLen);
            head.set(INT_BE, 2 * Integer.BYTES, rawLen);
            headLength = R7fConstants.ENTRY_CONTENT_HEADER_SIZE;
            fbSource = MemorySegment.ofBuffer(fbBuf);
            rawSource = rawLen > 0 ? MemorySegment.ofBuffer(rawData) : null;
            fbEnd = R7fConstants.ENTRY_CONTENT_HEADER_SIZE + (long) fbLen;
        }

        /**
         * Drops the references to the caller's buffers, which this thread-local would
         * otherwise keep alive until its next entry.
         */
        private void releaseSources()
        {
            fbSource = null;
            rawSource = null;
        }

        /**
         * Copies bytes {@code [from, from + length)} of the content into {@code target} at
         * {@code at}.
         */
        private void copyContent(final MemorySegment target, final long from, final long length, final long at)
        {
            long logical = from;
            long remaining = length;
            long destination = at;
            while (remaining > 0)
            {
                final MemorySegment source;
                final long offset;
                final long available;
                if (logical < headLength)
                {
                    source = head;
                    offset = logical;
                    available = headLength - logical;
                }
                else if (logical < fbEnd)
                {
                    source = fbSource;
                    offset = logical - headLength;
                    available = fbEnd - logical;
                }
                else
                {
                    source = rawSource;
                    offset = logical - fbEnd;
                    available = rawSource.byteSize() - offset;
                }
                final long count = Math.min(available, remaining);
                MemorySegment.copy(source, offset, target, destination, count);
                logical += count;
                destination += count;
                remaining -= count;
            }
        }
    }

    @Override
    public synchronized void close() throws IOException
    {
        if (closed)
        {
            return;
        }
        closed = true;
        try
        {
            if (segment != null)
            {
                finalizeActiveSegment();
            }
        }
        finally
        {
            // Rotation hands the retiring segment to a virtual thread, and virtual threads
            // are daemons. Returning from close() without waiting let the JVM exit with a
            // rotated segment still mapped and still named .flux — so a clean shutdown left
            // behind the one artifact that is supposed to mean the process died, and every
            // restart reported a recovery. Nothing is lost either way; the cost is that the
            // signal stops meaning anything.
            awaitPendingFinalizers();
            commitSignal.signal();
            commitSignal.close();
            if (faultAhead != null)
            {
                faultAhead.close();
            }
            if (stream != null)
            {
                stream.close();
            }
            if (single != null)
            {
                single.close();
            }

            // The provider is this journal's to close. Its warmer thread runs ahead of the
            // writer holding mapped, pre-allocated .flux segments, and nothing else holds
            // a reference to it — the gateway constructs one per shard and keeps only the
            // journal. Leaving it running meant a clean shutdown released the active segment
            // and abandoned the warmed one, mapping and all, so the next boot found a
            // pre-allocation to recover that no crash had produced.
            //
            // In a finally because sealing the active segment is the part that can fail, and
            // a failure there is exactly when an orphaned mapping is least welcome.
            provider.close();
        }

        final IOException failure = finalizerFailure;
        if (failure != null)
        {
            // A close() that returns normally is a statement that everything was sealed. It
            // was not.
            throw new IOException("A segment rotated before close could not be finalized", failure);
        }
    }

    /**
     * Waits for rotation finalizers to finish sealing and renaming their segments.
     * <p>
     * Bounded rather than indefinite, and deliberately. This runs while holding the
     * journal's monitor, and a finalizer's last act is to call the caller-supplied
     * {@code finishedJournalFileSupplier} — user code, which could in principle reach back
     * into this journal and block on that same monitor. A bounded wait turns that from a
     * shutdown that hangs for ever into one that is a few seconds slow and says why.
     */
    private void awaitPendingFinalizers()
    {
        for (final Thread finalizer : pendingFinalizers)
        {
            try
            {
                finalizer.join(FINALIZER_SHUTDOWN_TIMEOUT_MILLIS);
if (finalizer.isAlive())
{
    finalizerFailure = new IOException("A rotated segment was still being finalized after "
            + FINALIZER_SHUTDOWN_TIMEOUT_MILLIS + " ms");
    logger.error("A rotated segment was still being finalized after {} ms; shutting down "
                    + "anyway. Recovery will seal it at next start.",
            FINALIZER_SHUTDOWN_TIMEOUT_MILLIS);
}
            }
            catch (final InterruptedException e)
            {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while waiting for rotated segments to be finalized");
                return;
            }
        }
    }

    private void rotateSegment()
    {
        if (segment != null)
        {
            final MemorySegment retiringSegment = this.segment;
            final Arena retiringArena = this.arena;
            final Path retiringPath = this.activePath;
            final long finalPosition = this.position;

            // Stamp the seal record now, while the mapping is still open and this thread
            // still knows what it wrote. A reader can then check its own decode against the
            // segment's own account of itself, rather than inferring completeness from not
            // having hit anything unusual.
            stampSealRecord(retiringSegment, nextSequence, finalPosition);

            // Capture the bounds for the filename before resetting
            final long firstTs = this.segmentStartEpochMillis;
            final long lastTs = System.currentTimeMillis();

            if (logger.isDebugEnabled())
            {
                logger.debug("Rotating {} after {} entries and {} ms",
                        retiringPath.getFileName(),
                        nextSequence - R7fConstants.FIRST_ENTRY_SEQUENCE,
                        (System.nanoTime() - segmentStartNanos) / 1_000_000L);
            }

            this.segment = null;
            this.segmentView = null;
            this.arena = null;

            // Built unstarted and registered first. Starting it and then recording it
            // leaves a window in which close() sees an empty set and returns while a segment
            // is still mapped and still named .flux.
            final Thread finalizer = Thread.ofVirtual().unstarted(() -> {
                try
                {
                    // Pass the timestamps into the async finalizer
                    final Path finalizedPath = finalizeSegmentAsync(
                            retiringSegment,
                            retiringArena,
                            retiringPath,
                            finalPosition,
                            firstTs,
                            lastTs
                    );

                    if (finalizedPath != null)
                    {
                        finishedJournalFileSupplier.accept(finalizedPath);
                    }
                }
                catch (final IOException e)
                {
                    logger.error("Unable to rotate segment", e);
                    finalizerFailure = e;
                }
                finally
                {
                    pendingFinalizers.remove(Thread.currentThread());
                }
            });
            pendingFinalizers.add(finalizer);
            finalizer.start();
        }

        final R7fJournalProvider.WarmedSegment next = provider.getNextSegment();
        this.segment = next.segment();
        this.segmentView = segment.asByteBuffer();
        this.activePath = next.path();
        this.arena = next.arena();

        writePreamble(next.segmentSequence());
        if (faultAhead != null)
        {
            faultAhead.reset(segment, position);
        }
        // The retiring segment's seal record is stamped and the new one has a preamble: a
        // reader woken now can finish the one and start the other.
        commitSignal.signal();
    }

    @SuppressWarnings("unused")
    private Path finalizeSegmentAsync(
            final MemorySegment oldSegment,
            final Arena oldArena,
            final Path oldPath,
            final long finalPosition,
            final long firstTs,
            final long lastTs) throws IOException
    {
        // A shared arena refuses to close while a native call is populating one of its pages
        if (faultAhead != null)
        {
            faultAhead.drain();
        }

        // 1. Unmap the memory. The OS flushes any remaining dirty pages to disk.
        oldArena.close();

        // Deliberately not truncated. Rotation only happens when a segment is full, so the
        // tail here is at most one entry's worth — and the seal record already says where
        // the data ends, so nothing infers it from the file's size. Truncation is kept for
        // clean close and recovery, where a segment can be sealed with most of its
        // pre-allocation unused.

        // Delete or rename
        if (finalPosition <= R7fConstants.PREAMBLE_SIZE)
        {
            Files.delete(oldPath);
            return null;
        }

        final Path target = oldPath.resolveSibling(sealedName(oldPath, firstTs, lastTs));
        Files.move(oldPath, target, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    private void finalizeActiveSegment() throws IOException
    {
        final long finalPosition = this.position;
        stampSealRecord(segment, nextSequence, finalPosition);
        segment.force();
        if (faultAhead != null)
        {
            faultAhead.drain();
        }
        arena.close();

        final long firstTs = this.segmentStartEpochMillis;
        final long lastTs = System.currentTimeMillis();

        segment = null;
        segmentView = null;
        arena = null;

        if (finalPosition <= R7fConstants.PREAMBLE_SIZE)
        {
            Files.delete(activePath);
            return;
        }

        // The pre-allocated tail stays. Nothing infers where the data ends from the file's
        // size any more — the seal record says so — and shrinking a file another process may
        // have mapped is the one remaining way this writer could take the tailer down with a
        // SIGBUS. A sealed segment is read and deleted within a tick or two, so the unused
        // remainder is transient.
        final Path target = activePath.resolveSibling(sealedName(activePath, firstTs, lastTs));
        Files.move(activePath, target, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Records what this segment contains, in the segment itself.
     * <p>
     * The count and the last sequence are written first, then the seal magic behind a fence.
     * A reader that finds the magic can trust the two facts behind it; one that does not
     * knows the segment was never properly sealed, which is otherwise invisible because
     * "sealed" is normally carried only by the file's extension.
     * <p>
     * Written once per segment, at seal, so it costs nothing on the write path.
     */
    private static void stampSealRecord(final MemorySegment target, final int nextSequenceAfterLast, final long dataEnd)
    {
        final long entryCount = nextSequenceAfterLast - (long) R7fConstants.FIRST_ENTRY_SEQUENCE;
        target.set(LONG_BE, R7fConstants.PREAMBLE_OFF_ENTRY_COUNT, entryCount);
        target.set(INT_BE, R7fConstants.PREAMBLE_OFF_LAST_SEQUENCE, nextSequenceAfterLast - 1);
        target.set(LONG_BE, R7fConstants.PREAMBLE_OFF_DATA_END, dataEnd);
        // No flags: a segment the writer sealed itself holds nothing beyond its Data End.
        target.set(INT_BE, R7fConstants.PREAMBLE_OFF_SEAL_FLAGS, 0);
        VarHandle.releaseFence();
        target.set(INT_BE, R7fConstants.PREAMBLE_OFF_SEAL_MAGIC, R7fConstants.SEAL_MAGIC);
    }

    private static String sealedName(final Path activePath, final long firstTs, final long lastTs)
    {
        final String baseName = activePath.getFileName().toString()
                .replace(R7fConstants.ACTIVE_FILE_EXTENSION, "");

        return String.format("%s-%d-%d%s", baseName, firstTs, lastTs, R7fConstants.R7F_FILE_EXTENSION);
    }

    /**
     * Rotates when an entry of {@code contentLength} bytes, with its fragment headers and any
     * padding before it, does not fit the rest of the active segment. An entry never spans
     * segments.
     */
    private void ensureCapacity(final long contentLength)
    {
        if (closed)
        {
            throw new IllegalStateException("Journal is closed");
        }

        final long freshStart = R7fFraming.entryStart(R7fConstants.PREAMBLE_SIZE, BLOCK_SIZE);
        if (R7fFraming.entryEnd(freshStart, contentLength, BLOCK_SIZE) > provider.getSegmentSizeBytes())
        {
            // Rotating would not help: no segment can ever hold this entry. Fail before
            // burning a freshly warmed segment on it.
            throw new IllegalStateException(
                    "Entry of " + contentLength + " bytes can never fit a segment of "
                            + provider.getSegmentSizeBytes() + " bytes (minus a "
                            + R7fConstants.PREAMBLE_SIZE + " byte preamble and the fragment headers)");
        }

        if (segment == null
                || R7fFraming.entryEnd(R7fFraming.entryStart(position, BLOCK_SIZE), contentLength, BLOCK_SIZE) > segment.byteSize())
        {
            rotateSegment();
        }
    }

    private void putLong(long v)
    {
        segment.set(LONG_BE, position, v);
        position += Long.BYTES;
    }

    /**
     * Writes the 1KB preamble. The remainder is left as the zero fill of the freshly
     * allocated file, which is what marks "no entry was ever written here" for readers.
     *
     * @param segmentSequence monotonic per-shard segment counter
     */
    private void writePreamble(final long segmentSequence)
    {
        position = 0;
        segmentStartEpochMillis = System.currentTimeMillis();
        segmentStartNanos = System.nanoTime();
        nextSequence = R7fConstants.FIRST_ENTRY_SEQUENCE;

        putInt(R7fConstants.MAGIC);
        putShort(R7fConstants.CURRENT_VERSION);
        putLong(segmentSequence);
        putLong(segmentStartEpochMillis);
        segment.set(INT_BE, R7fConstants.PREAMBLE_OFF_BLOCK_SIZE, BLOCK_SIZE);
        segment.set(SHORT_BE, R7fConstants.PREAMBLE_OFF_CODEC, codec);
        streamBlock = -1;
        position = R7fConstants.PREAMBLE_SIZE;
    }

    private void putInt(int v)
    {
        segment.set(INT_BE, position, v);
        position += Integer.BYTES;
    }

    private void putShort(short v)
    {
        segment.set(SHORT_BE, position, v);
        position += Short.BYTES;
    }

    public Path getActivePath()
    {
        return activePath;
    }

    public long getOffset()
    {
        return position;
    }

    /**
     * Sequence number that the next entry written to the active segment will carry.
     */
    public synchronized int getNextSequence()
    {
        return nextSequence;
    }
}
