package com.ethlo.venturi.vlf;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import java.util.zip.CRC32C;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.journal.api.Journal;
import com.ethlo.venturi.journal.api.JournalLevel;
import com.ethlo.venturi.vlf.fbs.ClientRequest;
import com.ethlo.venturi.vlf.fbs.ClientResponse;
import com.ethlo.venturi.vlf.fbs.EndExchange;
import com.ethlo.venturi.vlf.fbs.EventPayload;
import com.ethlo.venturi.vlf.fbs.FbsJournalLevel;
import com.ethlo.venturi.vlf.fbs.Header;
import com.ethlo.venturi.vlf.fbs.JournalEvent;
import com.ethlo.venturi.vlf.fbs.RequestBody;
import com.ethlo.venturi.vlf.fbs.ResponseBody;
import com.ethlo.venturi.vlf.fbs.UpstreamRequest;
import com.ethlo.venturi.vlf.fbs.UpstreamResponse;
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
    public synchronized void clientRequest(JournalLevel level, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers)
    {
        fbb.clear();
        int reqIdOff = fbb.createByteVector(asciiScratch, 0, copyToScratch(reqId));
        int lineOff = fbb.createByteVector(startLine);
        int headOff = buildHeadersVector(headers);

        ClientRequest.startClientRequest(fbb);
        ClientRequest.addJournalLevel(fbb, fbsJournalLevels[level.ordinal()]);
        ClientRequest.addReqId(fbb, reqIdOff);
        ClientRequest.addStartLine(fbb, lineOff);
        ClientRequest.addHeaders(fbb, headOff);
        finishAndWrite(EventPayload.ClientRequest, ClientRequest.endClientRequest(fbb));
    }

    @Override
    public synchronized void upstreamRequest(JournalLevel level, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers)
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
        finishAndWrite(EventPayload.UpstreamRequest, UpstreamRequest.endUpstreamRequest(fbb));
    }

    @Override
    public synchronized void upstreamResponse(JournalLevel level, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers)
    {
        fbb.clear();
        int reqIdOff = fbb.createByteVector(asciiScratch, 0, copyToScratch(reqId));
        int lineOff = fbb.createByteVector(startLine);
        int headOff = buildHeadersVector(headers);

        UpstreamResponse.startUpstreamResponse(fbb);
        UpstreamResponse.addJournalLevel(fbb, fbsJournalLevels[level.ordinal()]);
        UpstreamResponse.addReqId(fbb, reqIdOff);
        UpstreamResponse.addStartLine(fbb, lineOff);
        UpstreamResponse.addHeaders(fbb, headOff);
        finishAndWrite(EventPayload.UpstreamResponse, UpstreamResponse.endUpstreamResponse(fbb));
    }

    @Override
    public synchronized void clientResponse(JournalLevel level, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers)
    {
        fbb.clear();
        int reqIdOff = fbb.createByteVector(asciiScratch, 0, copyToScratch(reqId));
        int lineOff = fbb.createByteVector(startLine);
        int headOff = buildHeadersVector(headers);

        ClientResponse.startClientResponse(fbb);
        ClientResponse.addJournalLevel(fbb, fbsJournalLevels[level.ordinal()]);
        ClientResponse.addReqId(fbb, reqIdOff);
        ClientResponse.addStartLine(fbb, lineOff);
        ClientResponse.addHeaders(fbb, headOff);
        finishAndWrite(EventPayload.ClientResponse, ClientResponse.endClientResponse(fbb));
    }

    /* ============================================================
       BODY DATA CHANNELS
       ============================================================ */

    @Override
    public synchronized void requestBody(CharSequence reqId, ByteBuffer data)
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
        finishAndWrite(EventPayload.RequestBody, RequestBody.endRequestBody(fbb), data);
    }

    @Override
    public synchronized void responseBody(CharSequence reqId, ByteBuffer data)
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
        finishAndWrite(EventPayload.ResponseBody, ResponseBody.endResponseBody(fbb), data);
    }

    @Override
    public synchronized void endExchange(CharSequence reqId, GatewayAttributes attributes, int status, long sent, long recv, long duration, long reqCrc, long resCrc)
    {
        fbb.clear();
        int reqIdOff = fbb.createByteVector(asciiScratch, 0, copyToScratch(reqId));
        int attrVecOff = buildAttributesVector(attributes);

        EndExchange.startEndExchange(fbb);
        EndExchange.addReqId(fbb, reqIdOff);
        EndExchange.addTimestamp(fbb, System.currentTimeMillis());
        EndExchange.addStatus(fbb, status);
        EndExchange.addBytesSent(fbb, sent);
        EndExchange.addBytesReceived(fbb, recv);
        EndExchange.addDuration(fbb, duration);
        EndExchange.addAttributes(fbb, attrVecOff);
        EndExchange.addRequestCrc32c(fbb, (int) reqCrc);
        EndExchange.addResponseCrc32c(fbb, (int) resCrc);
        finishAndWrite(EventPayload.EndExchange, EndExchange.endEndExchange(fbb));
    }

    /* ============================================================
       PRIVATE LOGIC & UTILITIES
       ============================================================ */

    private void finishAndWrite(byte type, int offset)
    {
        finishAndWrite(type, offset, null);
    }

    private void finishAndWrite(byte type, int offset, ByteBuffer rawData)
    {
        JournalEvent.startJournalEvent(fbb);
        JournalEvent.addEventType(fbb, type);
        JournalEvent.addEvent(fbb, offset);
        fbb.finish(JournalEvent.endJournalEvent(fbb));
        writeEntry(fbb, rawData);
    }

    private int buildHeadersVector(GatewayHeaders headers)
    {
        this.currentHeaderCount = 0;
        headers.forEach(this, (self, name, value) -> {
                    int nOff = self.fbb.createByteVector(self.asciiScratch, 0, self.copyToScratch(name));
                    int vOff = self.fbb.createByteVector(self.asciiScratch, 0, self.copyToScratch(value));
                    Header.startHeader(self.fbb);
                    Header.addName(self.fbb, nOff);
                    Header.addValue(self.fbb, vOff);
                    self.headerOffsetsScratch[self.currentHeaderCount++] = Header.endHeader(self.fbb);
                }
        );
        return currentHeaderCount == 0 ? 0 : createOffsetVector(headerOffsetsScratch, currentHeaderCount);
    }

    private int buildAttributesVector(GatewayAttributes attributes)
    {
        this.currentAttributeCount = 0;
        if (attributes != null)
        {
            attributes.forEach(this, (self, name, value) -> {
                        int nOff = self.fbb.createByteVector(self.asciiScratch, 0, self.copyToScratch(name));
                        int vOff = self.fbb.createByteVector(self.asciiScratch, 0, self.copyToScratch(value));
                        Header.startHeader(self.fbb);
                        Header.addName(self.fbb, nOff);
                        Header.addValue(self.fbb, vOff);
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

    private int copyToScratch(CharSequence s)
    {
        final int len = s.length();
        for (int i = 0; i < len; i++) asciiScratch[i] = (byte) s.charAt(i);
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

    private void writeEntry(FlatBufferBuilder fbBuilder, ByteBuffer rawData)
    {
        if (segment.byteSize() == 0)
        {
            throw new IllegalStateException("Segment not initialized");
        }

        final ByteBuffer fb = fbBuilder.dataBuffer().slice();
        final int fbLen = fb.remaining();
        final int rawLen = rawData != null ? rawData.remaining() : 0;

        final int payloadLen =
                Integer.BYTES + // fbLen
                        Integer.BYTES + // rawLen
                        fbLen +
                        rawLen;

        final int totalLen =
                Integer.BYTES + // magic
                        Integer.BYTES + // payloadLen
                        payloadLen +
                        Integer.BYTES;  // crc

        ensureCapacity(totalLen);

        // ---- header ----
        putInt(VlfConstants.MAGIC);
        putInt(payloadLen);
        putInt(fbLen);
        putInt(rawLen);

        // ---- CRC ----
        final CRC32C crc = new CRC32C();
        updateInt(crc, payloadLen);
        updateInt(crc, fbLen);
        updateInt(crc, rawLen);

        // ---- FlatBuffer ----
        final ByteBuffer fbSlice = fb.slice();
        crc.update(fbSlice.duplicate());

        MemorySegment.copy(
                MemorySegment.ofBuffer(fbSlice),
                0,
                segment,
                position,
                fbLen
        );
        position += fbLen;

        // ---- Raw ----
        if (rawLen > 0)
        {
            final ByteBuffer rawSlice = rawData.slice();
            crc.update(rawSlice.duplicate());

            MemorySegment.copy(
                    MemorySegment.ofBuffer(rawSlice),
                    0,
                    segment,
                    position,
                    rawLen
            );
            position += rawLen;
            rawData.position(rawData.position() + rawLen);
        }

        // ---- CRC footer ----
        putInt((int) crc.getValue());
    }

    private void preFaultSegment(MemorySegment segment)
    {
        segment.fill((byte) 0);
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

    private void putByte(byte v)
    {
        segment.set(JAVA_BYTE, position, v);
        position += 1;
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