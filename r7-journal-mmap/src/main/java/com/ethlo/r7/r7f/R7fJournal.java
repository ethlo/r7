package com.ethlo.r7.r7f;

import static java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;
import java.util.zip.CRC32C;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.api.GatewayAttributes;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.Journal;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.r7f.fbs.ClientRequest;
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
import com.google.flatbuffers.FlatBufferBuilder;

public final class R7fJournal implements Journal
{
    private static final Logger logger = LoggerFactory.getLogger(R7fJournal.class);
    private static final ValueLayout.OfInt INT_BE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfShort SHORT_BE = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

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

    private static final int INITIAL_HEADER_SLOTS = 1024;
    private static final int INITIAL_ATTRIBUTE_SLOTS = 128;

    private final FlatBufferBuilder fbb = new FlatBufferBuilder(8192);

    private final R7fJournalProvider provider;
    private final Consumer<Path> finishedJournalFileSupplier;

    private final byte[] fbsJournalLevels = new byte[]{
            FbsJournalLevel.NONE,
            FbsJournalLevel.METADATA,
            FbsJournalLevel.HEADERS,
            FbsJournalLevel.FULL,
    };
    private final CRC32C crc = new CRC32C();

    private byte[] asciiScratch = new byte[INITIAL_SCRATCH];
    private int[] headerOffsetsScratch = new int[INITIAL_HEADER_SLOTS];
    private int[] attributeOffsetsScratch = new int[INITIAL_ATTRIBUTE_SLOTS];

    private MemorySegment segment;
    private Arena arena;
    private Path activePath;
    private long position;
    private int currentHeaderCount;
    private int currentAttributeCount;
    private boolean closed;

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
        rotateSegment();
    }

    public R7fJournal(final R7fJournalProvider provider)
    {
        this(provider, _ -> {
                }
        );
    }

    @Override
    public synchronized int clientRequest(JournalLevel level, String reqId, ByteBuffer startLine, GatewayHeaders headers, final InetAddress inetAddress, IpSource ipSource)
    {
        fbb.clear();
        int reqIdOff = fbb.createByteVector(asciiScratch, 0, copyToScratch(reqId));
        int lineOff = fbb.createByteVector(startLine);
        int headOff = buildHeadersVector(headers);
        int remoteAddressOff = fbb.createByteVector(inetAddress.getAddress());

        ClientRequest.startClientRequest(fbb);
        ClientRequest.addJournalLevel(fbb, fbsJournalLevels[level.ordinal()]);
        ClientRequest.addReqId(fbb, reqIdOff);
        ClientRequest.addStartLine(fbb, lineOff);
        ClientRequest.addHeaders(fbb, headOff);
        ClientRequest.addClientIp(fbb, remoteAddressOff);
        ClientRequest.addClientIpSource(fbb, ipSource.byteValue());
        return finishAndWrite(EventPayload.ClientRequest, ClientRequest.endClientRequest(fbb));
    }

    @Override
    public synchronized int upstreamRequest(JournalLevel level, String reqId, ByteBuffer startLine, GatewayHeaders headers)
    {
        fbb.clear();
        int reqIdOff = fbb.createByteVector(asciiScratch, 0, copyToScratch(reqId));
        int lineOff = fbb.createByteVector(startLine);
        int headOff = buildHeadersVector(headers);

        UpstreamRequest.startUpstreamRequest(fbb);
        UpstreamRequest.addJournalLevel(fbb, fbsJournalLevels[level.ordinal()]);
        UpstreamRequest.addReqId(fbb, reqIdOff);
        UpstreamRequest.addStartLine(fbb, lineOff);
        UpstreamRequest.addHeaders(fbb, headOff);
        return finishAndWrite(EventPayload.UpstreamRequest, UpstreamRequest.endUpstreamRequest(fbb));
    }

    @Override
    public synchronized int upstreamResponse(JournalLevel level, String reqId, int statusCode, ByteBuffer startLine, GatewayHeaders headers)
    {
        fbb.clear();
        int reqIdOff = fbb.createByteVector(asciiScratch, 0, copyToScratch(reqId));
        int lineOff = fbb.createByteVector(startLine);
        int headOff = buildHeadersVector(headers);

        UpstreamResponse.startUpstreamResponse(fbb);
        UpstreamResponse.addJournalLevel(fbb, fbsJournalLevels[level.ordinal()]);
        UpstreamResponse.addReqId(fbb, reqIdOff);
        UpstreamResponse.addStatus(fbb, statusCode);
        UpstreamResponse.addStartLine(fbb, lineOff);
        UpstreamResponse.addHeaders(fbb, headOff);
        return finishAndWrite(EventPayload.UpstreamResponse, UpstreamResponse.endUpstreamResponse(fbb));
    }

    @Override
    public synchronized int clientResponse(JournalLevel level, String reqId, int statusCode, ByteBuffer startLine, GatewayHeaders headers)
    {
        fbb.clear();
        int reqIdOff = fbb.createByteVector(asciiScratch, 0, copyToScratch(reqId));
        int lineOff = fbb.createByteVector(startLine);
        int headOff = buildHeadersVector(headers);

        ClientResponse.startClientResponse(fbb);
        ClientResponse.addJournalLevel(fbb, fbsJournalLevels[level.ordinal()]);
        ClientResponse.addReqId(fbb, reqIdOff);
        ClientResponse.addStatus(fbb, statusCode);
        ClientResponse.addStartLine(fbb, lineOff);
        ClientResponse.addHeaders(fbb, headOff);
        return finishAndWrite(EventPayload.ClientResponse, ClientResponse.endClientResponse(fbb));
    }

    /* ============================================================
       BODY DATA CHANNELS
       ============================================================ */

    @Override
    public synchronized int requestBody(String reqId, ByteBuffer data)
    {
        if (!data.hasRemaining())
        {
            throw new IllegalStateException("No data available for request body");
        }
        fbb.clear();
        int reqIdOff = fbb.createByteVector(asciiScratch, 0, copyToScratch(reqId));
        RequestBody.startRequestBody(fbb);
        RequestBody.addReqId(fbb, reqIdOff);
        RequestBody.addLength(fbb, data.remaining());
        return finishAndWrite(EventPayload.RequestBody, RequestBody.endRequestBody(fbb), data);
    }

    @Override
    public synchronized int responseBody(String reqId, ByteBuffer data)
    {
        if (!data.hasRemaining())
        {
            throw new IllegalStateException("No data available for response body");
        }
        fbb.clear();
        int reqIdOff = fbb.createByteVector(asciiScratch, 0, copyToScratch(reqId));
        ResponseBody.startResponseBody(fbb);
        ResponseBody.addReqId(fbb, reqIdOff);
        ResponseBody.addLength(fbb, data.remaining());
        return finishAndWrite(EventPayload.ResponseBody, ResponseBody.endResponseBody(fbb), data);
    }

    @Override
    public synchronized int endExchange(String reqId, GatewayAttributes attributes, final long requestStartTs, final long requestEndTs, int statusCode, long requestHeaderBytes, long requestBodyBytes, long responseHeaderBytes, long responseBodyBytes, final long proxyStartTs, final long proxyFirstByteReceivedTs, final long proxyEndTs, final int requestCheckSumValue, final int responseChecksumValue)
    {
        fbb.clear();
        int reqIdOff = fbb.createByteVector(asciiScratch, 0, copyToScratch(reqId));
        int attrVecOff = buildAttributesVector(attributes);

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
        EndExchange.addRequestCrc32c(fbb, requestCheckSumValue);
        EndExchange.addResponseCrc32c(fbb, responseChecksumValue);
        return finishAndWrite(EventPayload.EndExchange, EndExchange.endEndExchange(fbb));
    }

    /* ============================================================
       PRIVATE LOGIC & UTILITIES
       ============================================================ */

    private int finishAndWrite(byte type, int offset)
    {
        return finishAndWrite(type, offset, null);
    }

    private int finishAndWrite(byte type, int offset, ByteBuffer rawData)
    {
        JournalEvent.startJournalEvent(fbb);
        JournalEvent.addEventType(fbb, type);
        JournalEvent.addEvent(fbb, offset);
        fbb.finish(JournalEvent.endJournalEvent(fbb));
        return writeEntry(fbb, rawData);
    }

    private int writeEntry(FlatBufferBuilder fbBuilder, ByteBuffer rawData)
    {
        final ByteBuffer fbBuf = fbBuilder.dataBuffer();
        final int fbLen = fbBuf.remaining();
        final MemorySegment fbSource = MemorySegment.ofBuffer(fbBuf);

        final int rawLen = (rawData != null) ? rawData.remaining() : 0;

        // payloadLen as the format defines it: the two length fields plus the payload.
        final int payloadLen = Integer.BYTES + Integer.BYTES + fbLen + rawLen;
        // Physical size on disk: the fixed header (which already contains those two length
        // fields), the payload itself, and the CRC footer. Adding payloadLen here would
        // count the length fields twice.
        final int totalLen = R7fConstants.ENTRY_HEADER_SIZE + fbLen + rawLen + Integer.BYTES;

        ensureCapacity(totalLen);

        final int sequence = nextSequence;

        // Header block
        putInt(R7fConstants.MAGIC);
        putInt(sequence);
        putInt(payloadLen);
        putInt(fbLen);
        putInt(rawLen);

        // CRC covers everything but the magic: the sequence, the three lengths and the payload.
        crc.reset();
        updateInt(crc, sequence);
        updateInt(crc, payloadLen);
        updateInt(crc, fbLen);
        updateInt(crc, rawLen);
        crc.update(fbBuf.duplicate());

        // Copy FlatBuffer
        MemorySegment.copy(fbSource, 0, segment, position, fbLen);
        position += fbLen;

        // Handle Body Chunks
        if (rawLen > 0)
        {
            final MemorySegment rawSource = MemorySegment.ofBuffer(rawData);
            crc.update(rawData.duplicate());
            MemorySegment.copy(rawSource, 0, segment, position, rawLen);
            position += rawLen;
            rawData.position(rawData.position() + rawLen);
        }

        // Write CRC footer
        putInt((int) crc.getValue());

        nextSequence++;

        return totalLen; // Returning the total binary size of the entry
    }

    private int buildHeadersVector(GatewayHeaders headers)
    {
        this.currentHeaderCount = 0;
        headers.forEach(this, (self, name, value) ->
                {
                    self.headerOffsetsScratch = ensureSlot(self.headerOffsetsScratch, self.currentHeaderCount);
                    headerWrite(self, name, value);
                    self.headerOffsetsScratch[self.currentHeaderCount++] = Header.endHeader(self.fbb);
                }
        );
        return currentHeaderCount == 0 ? 0 : createOffsetVector(headerOffsetsScratch, currentHeaderCount);
    }

    private void headerWrite(final R7fJournal self, final String name, final String value)
    {
        int nOff = self.fbb.createByteVector(self.asciiScratch, 0, self.copyToScratch(name));
        int vOff = self.fbb.createByteVector(self.asciiScratch, 0, self.copyToScratch(value));
        Header.startHeader(self.fbb);
        Header.addName(self.fbb, nOff);
        Header.addValue(self.fbb, vOff);
    }

    private int buildAttributesVector(GatewayAttributes attributes)
    {
        this.currentAttributeCount = 0;
        if (attributes != null)
        {
            attributes.forEach(this, (self, name, value) -> {
                        self.attributeOffsetsScratch = ensureSlot(self.attributeOffsetsScratch, self.currentAttributeCount);
                        headerWrite(self, name, value);
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

    private int createOffsetVector(int[] offsets, int count)
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
    private int copyToScratch(String str)
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

    private void updateInt(CRC32C crc, int v)
    {
        crc.update((v >>> 24) & 0xFF);
        crc.update((v >>> 16) & 0xFF);
        crc.update((v >>> 8) & 0xFF);
        crc.update(v & 0xFF);
    }

    @Override
    public synchronized void close() throws IOException
    {
        if (closed)
        {
            return;
        }
        closed = true;
        if (segment != null)
        {
            finalizeActiveSegment();
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
            this.arena = null;

            Thread.startVirtualThread(() -> {
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
                }
            });
        }

        final R7fJournalProvider.WarmedSegment next = provider.getNextSegment();
        this.segment = next.segment();
        this.activePath = next.path();
        this.arena = next.arena();

        writePreamble(next.segmentSequence());
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
        // 1. Unmap the memory. The OS flushes any remaining dirty pages to disk.
        oldArena.close();

        // 2. Open a transient channel strictly to truncate the file
        try (FileChannel fc = FileChannel.open(oldPath, StandardOpenOption.WRITE))
        {
            fc.truncate(finalPosition);
        }

        // 3. Delete or Rename
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
        segment.force();
        arena.close();

        final long firstTs = this.segmentStartEpochMillis;
        final long lastTs = System.currentTimeMillis();

        segment = null;
        arena = null;

        if (position <= R7fConstants.PREAMBLE_SIZE)
        {
            Files.delete(activePath);
        }
        else
        {
            final Path target = activePath.resolveSibling(sealedName(activePath, firstTs, lastTs));
            Files.move(activePath, target, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private static String sealedName(final Path activePath, final long firstTs, final long lastTs)
    {
        final String baseName = activePath.getFileName().toString()
                .replace(R7fConstants.ACTIVE_FILE_EXTENSION, "");

        return String.format("%s-%d-%d%s", baseName, firstTs, lastTs, R7fConstants.R7F_FILE_EXTENSION);
    }

    private void ensureCapacity(long needed)
    {
        if (closed)
        {
            throw new IllegalStateException("Journal is closed");
        }

        if (needed > provider.getSegmentSizeBytes() - R7fConstants.PREAMBLE_SIZE)
        {
            // Rotating would not help: no segment can ever hold this entry. Fail before
            // burning a freshly warmed segment on it.
            throw new IllegalStateException(
                    "Entry of " + needed + " bytes can never fit a segment of "
                            + provider.getSegmentSizeBytes() + " bytes (minus a "
                            + R7fConstants.PREAMBLE_SIZE + " byte preamble)");
        }

        if (segment == null || position + needed > segment.byteSize())
        {
            rotateSegment();
        }
    }

    private void putLong(long v)
    {
        segment.set(JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), position, v);
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
