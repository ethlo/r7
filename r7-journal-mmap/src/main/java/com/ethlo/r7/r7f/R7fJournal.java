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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
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
     * assumption {@code StartLineBuilder} and {@code Fingerprint} already make on this path. A
     * write arriving on a short-lived virtual thread costs that thread its own encoder rather
     * than correctness.
     */
    private final ThreadLocal<EntryEncoder> encoders = ThreadLocal.withInitial(EntryEncoder::new);

    private final R7fJournalProvider provider;

    /**
     * Bumped after every commit and rotation, so a tailer can wait for news without polling
     * the segment (design/history/live-tailing.md).
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
     * Plain bytes a stage holds before it is compressed and placed as one batch (FORMAT.md 4.4).
     * Larger batches compress a little better, and cost more entries when one is damaged and
     * a longer wait for a tailer at low traffic.
     */
    static final int STAGE_SIZE = 32 * 1024;

    /**
     * How long an entry may wait in a stage that is not full before the writer thread places
     * it anyway. This, not the stage size, is what a tailer waits at low traffic.
     */
    static final long FLUSH_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    /**
     * zstd level (FORMAT.md 4.4), or 0 for none: the provider's, which has already fallen back
     * to 0 where zstd cannot be loaded.
     */
    private final int compressionLevel;
    private final short codec;

    // Compression state, guarded by this journal's monitor
    private ZstdCompressCtx zstd;
    private ByteBuffer packed;
    private MemorySegment packedSegment;

    /**
     * Staging for a compressed journal, null for an uncompressed one. Request threads copy
     * entries into it and one writer thread places them; see {@link Batcher}.
     */
    private final Batcher batcher;

    /** Record bytes placed in segments, after compression: what this shard costs on disk. */
    private final LongAdder bytesPlaced = new LongAdder();

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
        this.codec = compressionLevel > 0 ? R7fConstants.CODEC_ZSTD_BATCH : R7fConstants.CODEC_NONE;
        this.commitSignal = provider.openCommitSignal();
        rotateSegment();
        this.batcher = compressionLevel > 0 ? new Batcher(provider.getShardId()) : null;
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
        if (batcher != null)
        {
            return batcher.stage(enc, rawData);
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
        bytesPlaced.add(end - claimedFrom);
        commitSignal.signal();

        if (faultAhead != null)
        {
            faultAhead.advance(segment, position);
        }

        // The entry's content, not what it took in the segment: callers count plain bytes, and
        // fragment headers and padding here would give the count a different unit than a
        // compressed journal's. What it took is bytesPlaced.
        return (int) contentLength;
    }

    /**
     * Compresses {@code count} entries, {@code plainLength} bytes of {@code plain} starting at
     * its index 0, as one batch and commits it (FORMAT.md 4.4). The entries' Sequences are
     * stamped here, at {@code sequenceOffsets}, because only now is it known which segment the
     * batch lands in, and rotation restarts the Sequence.
     * <p>
     * Called by the writer thread, and by a request thread with an oversized entry while it
     * holds the staging lock and the writer thread is idle; the monitor keeps the two apart
     * all the same, as it does {@link #close}.
     *
     * @return the bytes the batch took in the segment
     */
    private synchronized long placeBatch(final EntryEncoder enc, final ByteBuffer plain, final int plainLength,
                                         final int[] sequenceOffsets, final int count)
    {
        final long bound = R7fConstants.BATCH_HEADER_SIZE + compressBound(plainLength);
        // Rotate on the worst case first, so the Sequences stamped below are this segment's
        ensureCapacity(bound);
        final long claimedFrom = position;
        for (int i = 0; i < count; i++)
        {
            plain.putInt(sequenceOffsets[i], nextSequence + i);
        }

        ensurePacked(bound);
        if (zstd == null)
        {
            zstd = new ZstdCompressCtx().setLevel(compressionLevel).setChecksum(false);
        }
        final int frame = zstd.compressDirectByteBuffer(packed, R7fConstants.BATCH_HEADER_SIZE,
                packed.capacity() - R7fConstants.BATCH_HEADER_SIZE, plain, 0, plainLength);
        packed.putInt(0, plainLength).putInt(Integer.BYTES, count);
        final long contentLength = R7fConstants.BATCH_HEADER_SIZE + (long) frame;
        enc.preparePackedSources(packedSegment, contentLength);
        final long publishAt = writeFragments(enc, contentLength);

        // The commit, as in writeEntry: every store above lands before the magic
        VarHandle.releaseFence();
        segment.set(INT_BE, publishAt, R7fConstants.FRAGMENT_MAGIC);

        enc.releaseSources();
        nextSequence += count;
        bytesPlaced.add(position - claimedFrom);
        commitSignal.signal();
        if (faultAhead != null)
        {
            faultAhead.advance(segment, position);
        }
        return position - claimedFrom;
    }

    /**
     * zstd's {@code ZSTD_COMPRESSBOUND}: the most a single-shot compression of
     * {@code plainLength} bytes can produce. Computed here rather than by
     * {@code Zstd.compressBound}, which is a JNI transition made while holding this journal's
     * monitor, for what is a line of arithmetic. {@code compressBoundMatchesZstd} keeps the
     * two equal.
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
    private long writeFragments(final EntryEncoder enc, final long contentLength)
    {
        final long start = R7fFraming.entryStart(position, BLOCK_SIZE);
        final long firstCapacity = R7fFraming.firstCapacity(start, BLOCK_SIZE);
        if (contentLength <= firstCapacity)
        {
            writeFragmentBody(enc, start, R7fConstants.FRAGMENT_FULL, 0, contentLength);
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
            writeFragmentBody(enc, at, type, logical, length);
            segment.set(INT_BE, at, R7fConstants.FRAGMENT_MAGIC);
            logical += length;
            last = at + R7fConstants.FRAGMENT_HEADER_SIZE + length;
            at += BLOCK_SIZE;
        }
        writeFragmentBody(enc, start, R7fConstants.FRAGMENT_FIRST, 0, firstCapacity);
        position = last;
        return start;
    }

    private void ensurePacked(final long capacity)
    {
        if (packed == null || packed.capacity() < capacity)
        {
            packed = ByteBuffer.allocateDirect((int) Math.max(capacity, 2L * BLOCK_SIZE));
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
        segment.set(ValueLayout.JAVA_BYTE, at + R7fConstants.FRAGMENT_OFF_TYPE, type);
        segment.set(ValueLayout.JAVA_BYTE, at + R7fConstants.FRAGMENT_OFF_FLAGS, (byte) 0);
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

        // An oversized entry only: its whole content in one direct buffer, Sequence left to
        // fill in, so that it can be compressed as a batch of its own
        private ByteBuffer plain;
        private int plainLength;
        private final int[] plainSequenceOffset = new int[1];

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

    /**
     * Places everything staged before this call, and returns once it is committed: for a
     * caller that has to see its entries in the segment, such as a test or a reader in the
     * same process. Without compression every write is committed before it returns, and this
     * returns at once.
     *
     * @throws IllegalStateException when the journal has failed or is closed
     */
    public void flush()
    {
        if (batcher != null)
        {
            batcher.flush();
        }
    }

    /**
     * The failure that stopped this journal accepting entries, or null while it accepts them.
     * Only a compressed journal fails this way: its writer thread places entries whose
     * requests have already completed, so a failure there cannot fail those requests and is
     * reported here instead (r7-journal-mmap/README.md 8.3).
     */
    @Override
    public Throwable failure()
    {
        return batcher != null ? batcher.failure : null;
    }

    /**
     * For tests that assert how entries are batched, which the flush interval would otherwise
     * make depend on how fast the machine is: a stage is then placed only when full, flushed
     * or closed.
     */
    void holdBatchesUntilFull()
    {
        batcher.lock.lock();
        try
        {
            batcher.flushIntervalNanos = Long.MAX_VALUE / 2;
        }
        finally
        {
            batcher.lock.unlock();
        }
    }

    /**
     * Bytes placed in segments, after compression: what this journal has cost on disk, which
     * the per-write return values (plain bytes) do not say once entries are compressed.
     */
    @Override
    public long bytesPlaced()
    {
        return bytesPlaced.sum();
    }

    /**
     * The staging between request threads and the segment, for a compressed journal.
     * <p>
     * Compressing each entry on the request thread cost more than everything else the journal
     * does: a flush per entry is expensive whatever the level, and since a block's stream is
     * shared it ran under the monitor, so every request on the shard queued behind it
     * (design/history/journal-batch-compression.md). Here a request thread only copies its
     * entry into a stage under a short lock, and one writer thread compresses a whole stage as
     * one batch and places it.
     * <p>
     * Two stages: one filling while the writer places the other. A request thread that finds
     * its stage full waits for the writer to hand the other back, which is the journal's
     * backpressure, as the monitor was before. Entries are never dropped.
     * <p>
     * What this costs is stated in r7-journal-mmap/README.md 4.0 and 8.1: an entry is in the segment only once its batch
     * is placed, so a crash loses what is staged, and a tailer sees an entry up to
     * {@link #FLUSH_INTERVAL_NANOS} later than it would uncompressed.
     */
    private final class Batcher
    {
        private final ReentrantLock lock = new ReentrantLock();
        /** Signalled to the writer thread: a stage has entries, is full, or is wanted now. */
        private final Condition work = lock.newCondition();
        /** Signalled to request threads: a stage came back, or the journal stopped. */
        private final Condition stageFree = lock.newCondition();

        /** Where request threads append. Never null. */
        private Stage filling = new Stage();
        /** The other stage while the writer thread is not placing it, else null. */
        private Stage spare = new Stage();
        /** Bumped each time a stage is placed, so {@link #flush} can wait for its own. */
        private long placed;
        private long flushRequested = -1;
        private boolean stopping;
        private volatile Throwable failure;

        /** Only the writer thread uses it: the encoder that frames each batch it places. */
        private final EntryEncoder writerEncoder = new EntryEncoder();
        private final Thread writer;

        /** {@link #FLUSH_INTERVAL_NANOS}, unless a test holds batches until their stage is full. */
        private long flushIntervalNanos = FLUSH_INTERVAL_NANOS;

        private Batcher(final int shard)
        {
            // A platform thread: it is busy for as long as the shard is, and a virtual thread
            // would pin its carrier inside the monitor anyway
            this.writer = Thread.ofPlatform().daemon().name("r7-journal-writer-" + shard).start(this::run);
        }

        /**
         * Copies the encoder's entry into the filling stage, with Sequence 0 for the writer
         * thread to stamp.
         *
         * @return the entry's plain bytes, which is what is known about it now: its compressed
         *         share of a batch is not, and is counted by {@link #bytesPlaced}
         */
        private int stage(final EntryEncoder enc, final ByteBuffer rawData)
        {
            final ByteBuffer fbBuf = enc.fbb.dataBuffer();
            final int fbLen = fbBuf.remaining();
            final int rawLen = rawData != null ? rawData.remaining() : 0;
            final long length = R7fConstants.ENTRY_CONTENT_HEADER_SIZE + (long) fbLen + rawLen;
            if (length > STAGE_SIZE)
            {
                return writeAlone(enc, fbBuf, rawData, length);
            }

            lock.lock();
            try
            {
                while (true)
                {
                    requireOpen();
                    final Stage stage = filling;
                    if (stage.plain.remaining() >= length)
                    {
                        if (stage.count == 0)
                        {
                            // Starts the flush interval
                            stage.firstNanos = System.nanoTime();
                            work.signal();
                        }
                        stage.add(fbBuf, fbLen, rawData, rawLen);
                        break;
                    }
                    stage.full = true;
                    work.signal();
                    stageFree.awaitUninterruptibly();
                }
            }
            finally
            {
                lock.unlock();
            }
            if (rawLen > 0)
            {
                rawData.position(rawData.position() + rawLen);
            }
            return (int) length;
        }

        /**
         * An entry larger than a stage, placed as a batch of its own on the calling thread.
         * <p>
         * It must not overtake what is already staged, or the order in the segment would stop
         * being the order of the writes. So it waits, holding the lock, until everything staged
         * is placed and the writer thread is idle, and places itself before letting anyone else
         * stage. It is compressed alone, which costs nothing it would gain in a batch: an entry
         * this large is a body, and compresses well on its own.
         */
        private int writeAlone(final EntryEncoder enc, final ByteBuffer fbBuf, final ByteBuffer rawData, final long length)
        {
            // Before waiting for anything: no segment can ever hold it, and that is the
            // caller's fault, not the shard's
            requireFitsASegment(R7fConstants.BATCH_HEADER_SIZE + compressBound((int) Math.min(length, Integer.MAX_VALUE - 1)));
            enc.fillPlain(fbBuf, rawData);
            lock.lock();
            try
            {
                while (true)
                {
                    requireOpen();
                    if (filling.count == 0 && spare != null)
                    {
                        break;
                    }
                    if (filling.count > 0)
                    {
                        filling.full = true;
                        work.signal();
                    }
                    stageFree.awaitUninterruptibly();
                }
                try
                {
                    placeBatch(enc, enc.plain, enc.plainLength, enc.plainSequenceOffset, 1);
                }
                catch (final RuntimeException | Error e)
                {
                    fail(e, 0);
                    throw e;
                }
            }
            finally
            {
                lock.unlock();
            }
            if (rawData != null && rawData.hasRemaining())
            {
                rawData.position(rawData.limit());
            }
            return (int) length;
        }

        private void flush()
        {
            lock.lock();
            try
            {
                requireOpen();
                if (filling.count == 0 && spare != null)
                {
                    return;
                }
                // Everything staged is in the stage being placed, if any, and the filling one
                final long target = placed + (spare == null ? 1 : 0) + (filling.count > 0 ? 1 : 0);
                flushRequested = target;
                work.signal();
                while (placed < target)
                {
                    requireOpen();
                    stageFree.awaitUninterruptibly();
                }
            }
            finally
            {
                lock.unlock();
            }
        }

        /** The writer thread: swap, place, hand back, until stopped. */
        private void run()
        {
            lock.lock();
            try
            {
                while (failure == null)
                {
                    final Stage stage = filling;
                    if (stage.count > 0 && (stage.full || stopping || placed < flushRequested
                            || System.nanoTime() - stage.firstNanos >= flushIntervalNanos))
                    {
                        filling = spare;
                        spare = null;
                        // Whoever waits for room can stage into the fresh one already
                        stageFree.signalAll();
                        Throwable failed = null;
                        lock.unlock();
                        try
                        {
                            placeBatch(writerEncoder, stage.plain, stage.plain.position(), stage.sequenceOffsets, stage.count);
                        }
                        catch (final Throwable e)
                        {
                            failed = e;
                        }
                        finally
                        {
                            lock.lock();
                        }
                        if (failed != null)
                        {
                            fail(failed, stage.count + filling.count);
                            return;
                        }
                        stage.clear();
                        spare = stage;
                        placed++;
                        stageFree.signalAll();
                    }
                    else if (stopping)
                    {
                        return;
                    }
                    else if (stage.count == 0)
                    {
                        work.awaitUninterruptibly();
                    }
                    else
                    {
                        final long waited = System.nanoTime() - stage.firstNanos;
                        try
                        {
                            work.awaitNanos(flushIntervalNanos - waited);
                        }
                        catch (final InterruptedException e)
                        {
                            // Nobody interrupts this thread but a JVM going down; keep the
                            // entries moving until stopped
                        }
                    }
                }
            }
            finally
            {
                lock.unlock();
            }
        }

        /**
         * The shard stops: a full disk or a lost mapping is the system's failure, and the
         * entries staged when it happened are not this journal's to save. What it owes is to
         * say so, here and through {@link #failure()}, and to refuse every later entry, so
         * that requests fail closed from now on instead of being journaled into nothing.
         * Called with the lock held.
         */
        private void fail(final Throwable cause, final int lostEntries)
        {
            failure = cause;
            filling.clear();
            logger.error("Journal shard {} stopped: {}. {} staged entr{} could not be placed and {} lost; "
                            + "the shard refuses entries from now on.",
                    provider.getShardId(), cause.toString(), lostEntries, lostEntries == 1 ? "y" : "ies",
                    lostEntries == 1 ? "is" : "are", cause);
            work.signal();
            stageFree.signalAll();
        }

        private void requireOpen()
        {
            final Throwable failed = failure;
            if (failed != null)
            {
                throw new IllegalStateException("Journal shard " + provider.getShardId() + " has failed", failed);
            }
            if (stopping)
            {
                throw new IllegalStateException("Journal is closed");
            }
        }

        /**
         * Places what is staged, then stops the writer thread.
         *
         * @return false if the writer was still running at the deadline
         */
        private boolean drainAndStop()
        {
            lock.lock();
            try
            {
                stopping = true;
                work.signal();
                stageFree.signalAll();
            }
            finally
            {
                lock.unlock();
            }
            // An interrupt must not end the wait: the segment is sealed right after this, and a
            // writer that has not placed its last batch yet would find the journal closed and
            // drop it. The interrupt is kept for the caller.
            final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FINALIZER_SHUTDOWN_TIMEOUT_MILLIS);
            boolean interrupted = false;
            long remaining;
            while (writer.isAlive() && (remaining = deadline - System.nanoTime()) > 0)
            {
                try
                {
                    TimeUnit.NANOSECONDS.timedJoin(writer, remaining);
                }
                catch (final InterruptedException e)
                {
                    interrupted = true;
                }
            }
            if (interrupted)
            {
                Thread.currentThread().interrupt();
            }
            if (writer.isAlive())
            {
                logger.error("Journal shard {}'s writer thread was still placing entries after {} ms; "
                        + "leaving the segment unsealed for recovery.", provider.getShardId(), FINALIZER_SHUTDOWN_TIMEOUT_MILLIS);
                return false;
            }
            return true;
        }
    }

    /** Entries staged for one batch: their plain content back to back (FORMAT.md 4.2). */
    private static final class Stage
    {
        private final ByteBuffer plain = ByteBuffer.allocateDirect(STAGE_SIZE);
        /** Where each entry's Sequence goes, for the writer thread to stamp. */
        private int[] sequenceOffsets = new int[64];
        private int count;
        private long firstNanos;
        private boolean full;

        private void add(final ByteBuffer fbBuf, final int fbLen, final ByteBuffer rawData, final int rawLen)
        {
            if (count == sequenceOffsets.length)
            {
                final int[] grown = new int[count * 2];
                System.arraycopy(sequenceOffsets, 0, grown, 0, count);
                sequenceOffsets = grown;
            }
            sequenceOffsets[count++] = plain.position();
            plain.putInt(0).putInt(fbLen).putInt(rawLen).put(fbBuf.duplicate());
            if (rawLen > 0)
            {
                plain.put(rawData.duplicate());
            }
        }

        private void clear()
        {
            plain.clear();
            count = 0;
            full = false;
        }
    }

    @Override
    public void close() throws IOException
    {
        // Outside the monitor: the writer thread takes it to place what is still staged
        if (batcher != null && !batcher.drainAndStop())
        {
            // Sealing now would close the segment under a writer that may still place a
            // batch, and that batch would be refused and lost. Left open, the segment stays
            // .flux and recovery seals it after what the writer did place.
            return;
        }
        closeSegments();
    }

    private synchronized void closeSegments() throws IOException
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
            if (zstd != null)
            {
                zstd.close();
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
     * Rotating would not help a record that no segment can ever hold. Fails before burning a
     * freshly warmed segment on it.
     */
    private void requireFitsASegment(final long contentLength)
    {
        final long freshStart = R7fFraming.entryStart(R7fConstants.PREAMBLE_SIZE, BLOCK_SIZE);
        if (R7fFraming.entryEnd(freshStart, contentLength, BLOCK_SIZE) > provider.getSegmentSizeBytes())
        {
            throw new IllegalStateException(
                    "Entry of " + contentLength + " bytes can never fit a segment of "
                            + provider.getSegmentSizeBytes() + " bytes (minus a "
                            + R7fConstants.PREAMBLE_SIZE + " byte preamble and the fragment headers)");
        }
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

        requireFitsASegment(contentLength);

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
