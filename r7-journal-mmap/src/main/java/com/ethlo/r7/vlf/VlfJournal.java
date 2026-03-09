package com.ethlo.r7.vlf;

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
import java.util.function.Consumer;
import java.util.zip.CRC32C;

import com.ethlo.r7.api.IpSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.api.GatewayAttributes;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.journal.api.Journal;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.vlf.fbs.ClientRequest;
import com.ethlo.r7.vlf.fbs.ClientResponse;
import com.ethlo.r7.vlf.fbs.EndExchange;
import com.ethlo.r7.vlf.fbs.EventPayload;
import com.ethlo.r7.vlf.fbs.FbsJournalLevel;
import com.ethlo.r7.vlf.fbs.Header;
import com.ethlo.r7.vlf.fbs.JournalEvent;
import com.ethlo.r7.vlf.fbs.RequestBody;
import com.ethlo.r7.vlf.fbs.ResponseBody;
import com.ethlo.r7.vlf.fbs.UpstreamRequest;
import com.ethlo.r7.vlf.fbs.UpstreamResponse;
import com.google.flatbuffers.FlatBufferBuilder;

public final class VlfJournal implements Journal
{
    private static final Logger logger = LoggerFactory.getLogger(VlfJournal.class);
    private static final ValueLayout.OfInt INT_BE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfShort SHORT_BE = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final int MAX_SCRATCH = 8192;

    private final FlatBufferBuilder fbb = new FlatBufferBuilder(8192);
    private final byte[] asciiScratch = new byte[MAX_SCRATCH];
    private final int[] headerOffsetsScratch = new int[1024];
    private final int[] attributeOffsetsScratch = new int[100];

    private final VlfJournalProvider provider;
    private final Consumer<Path> finishedJournalFileSupplier;

    private final byte[] fbsJournalLevels = new byte[]{
            FbsJournalLevel.NONE,
            FbsJournalLevel.METADATA,
            FbsJournalLevel.HEADERS,
            FbsJournalLevel.FULL,
    };
    private final CRC32C crc = new CRC32C();
    private MemorySegment segment;
    private Arena arena;
    private Path activePath;
    private long position;
    private FileChannel channel;
    private int currentHeaderCount;
    private int currentAttributeCount;

    public VlfJournal(VlfJournalProvider provider, final Consumer<Path> finishedJournalFileSupplier)
    {
        this.provider = provider;
        this.finishedJournalFileSupplier = finishedJournalFileSupplier;
        rotateSegment();
    }

    public VlfJournal(VlfJournalProvider provider)
    {
        this(provider, _ -> {
                }
        );
    }

    /* ============================================================
       THE FOUR METADATA SLICES
       ============================================================ */

    @Override
    public synchronized int clientRequest(JournalLevel level, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers, final InetAddress inetAddress, IpSource ipSource)
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
    public synchronized int upstreamRequest(JournalLevel level, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers)
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
    public synchronized int upstreamResponse(JournalLevel level, CharSequence reqId, int statusCode, ByteBuffer startLine, GatewayHeaders headers)
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
    public synchronized int clientResponse(JournalLevel level, CharSequence reqId, int statusCode, ByteBuffer startLine, GatewayHeaders headers)
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
    public synchronized int requestBody(CharSequence reqId, ByteBuffer data)
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
    public synchronized int responseBody(CharSequence reqId, ByteBuffer data)
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
    public synchronized int endExchange(CharSequence reqId, GatewayAttributes attributes, final long requestStartTs, final long requestEndTs, int statusCode, long requestHeaderBytes, long requestBodyBytes, long responseHeaderBytes, long responseBodyBytes, final long proxyStartTs, final long proxyFirstByteReceivedTs, final long proxyEndTs, final int requestCheckSumValue, final int responseChecksumValue)
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
        if (segment == null || segment.byteSize() == 0)
        {
            throw new IllegalStateException("Segment not initialized");
        }

        final ByteBuffer fbBuf = fbBuilder.dataBuffer();
        final int fbLen = fbBuf.remaining();
        final MemorySegment fbSource = MemorySegment.ofBuffer(fbBuf);

        final int rawLen = (rawData != null) ? rawData.remaining() : 0;

        // payloadLen: fbLen + rawLen + 2 ints for metadata
        final int payloadLen = Integer.BYTES + Integer.BYTES + fbLen + rawLen;
        // totalLen: MAGIC + payloadLen field + payload + CRC footer
        final int totalLen = Integer.BYTES + Integer.BYTES + payloadLen + Integer.BYTES;

        ensureCapacity(totalLen);

        // Header block
        putInt(VlfConstants.MAGIC);
        putInt(payloadLen);
        putInt(fbLen);
        putInt(rawLen);

        // CRC Calculation
        crc.reset();
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

        return totalLen; // Returning the total binary size of the VLF entry
    }

    private int buildHeadersVector(GatewayHeaders headers)
    {
        this.currentHeaderCount = 0;
        headers.forEach(this, (self, name, value) ->
                {
                    headerWrite(self, name, value);
                    self.headerOffsetsScratch[self.currentHeaderCount++] = Header.endHeader(self.fbb);
                }
        );
        return currentHeaderCount == 0 ? 0 : createOffsetVector(headerOffsetsScratch, currentHeaderCount);
    }

    private void headerWrite(final VlfJournal self, final CharSequence name, final CharSequence value)
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
                        headerWrite(self, name, value);
                        self.attributeOffsetsScratch[self.currentAttributeCount++] = Header.endHeader(self.fbb);
                    }
            );
        }
        return currentAttributeCount == 0 ? 0 : createOffsetVector(attributeOffsetsScratch, currentAttributeCount);
    }

    private int createOffsetVector(int[] offsets, int count)
    {
        fbb.startVector(4, count, 4);
        for (int i = count - 1; i >= 0; i--) fbb.addOffset(offsets[i]);
        return fbb.endVector();
    }

    @SuppressWarnings("deprecation")
    private int copyToScratch(CharSequence s)
    {
        final int len = s.length();
        if (s instanceof String str)
        {
            // Use the JVM's optimized, SIMD-enabled intrinsic
            str.getBytes(0, len, asciiScratch, 0);
            return len;
        }

        // Fallback for other CharSequence types
        for (int i = 0; i < len; i++)
        {
            asciiScratch[i] = (byte) s.charAt(i);
        }
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
        if (segment != null)
        {
            finalizeActiveSegment();
        }
    }

    private void rotateSegment()
    {
        // 1. Finalize the old segment if it exists
        if (segment != null)
        {
            // (Your logic here to write the EOF marker or truncate pre-faulted zeros)
            Path finalizedPath = null;
            try
            {
                finalizedPath = finalizeActiveSegment();
            }
            catch (IOException e)
            {
                logger.error("Unable to rotate segment", e);
            }

            if (finalizedPath != null)
            {
                // Trigger the delayed compression queue
                finishedJournalFileSupplier.accept(finalizedPath);
            }
        }

        // Instant O(1) swap to the pre-faulted segment
        final VlfJournalProvider.WarmedSegment next = provider.getNextSegment();
        this.segment = next.segment();
        this.activePath = next.path();
        this.arena = next.arena();

        // Write the VLF Preamble directly
        writePreamble();
    }

    private Path finalizeActiveSegment() throws IOException
    {
        segment.force();
        arena.close(); // Unmap BEFORE truncating

        if (channel != null && channel.isOpen())
        {
            // Shrink the file to remove the trailing pre-faulted zeros
            channel.truncate(position);
            channel.close();
        }

        // Clear references to prevent accidental use of closed resources
        segment = null;
        arena = null;
        channel = null;

        if (position <= VlfConstants.PREAMBLE_SIZE)
        {
            Files.delete(activePath);
            return null;
        }
        else
        {
            String newName = activePath.getFileName().toString().replace(VlfConstants.ACTIVE_FILE_EXTENSION, VlfConstants.VLF_FILE_EXTENSION);
            final Path target = activePath.resolveSibling(newName);
            Files.move(activePath, target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        }
    }

    private void ensureCapacity(long needed)
    {
        if (segment == null)
        {
            rotateSegment();
        }

        if (position + needed > segment.byteSize())
        {
            rotateSegment();
        }

        if (position + needed > segment.byteSize())
        {
            throw new IllegalStateException(
                    "Entry too large for segment. needed=" + needed +
                            " remaining=" + remaining());
        }
    }

    private long remaining()
    {
        return segment.byteSize() - position;
    }

    private void putLong(long v)
    {
        segment.set(JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), position, v);
        position += Long.BYTES;
    }

    private void writePreamble()
    {
        position = 0;

        putInt(VlfConstants.MAGIC);
        putShort(VlfConstants.VERSION_1);
        putLong(System.currentTimeMillis());
        position = VlfConstants.PREAMBLE_SIZE;
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
}