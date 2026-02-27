package com.ethlo.venturi.vlf;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED;

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
import com.ethlo.venturi.api.ServerDirection;
import com.ethlo.venturi.journal.api.Journal;
import com.ethlo.venturi.journal.api.JournalLevel;
import com.ethlo.venturi.vlf.fbs.BodyEvent;
import com.ethlo.venturi.vlf.fbs.EndEvent;
import com.ethlo.venturi.vlf.fbs.EventPayload;
import com.ethlo.venturi.vlf.fbs.Header;
import com.ethlo.venturi.vlf.fbs.JournalEvent;
import com.ethlo.venturi.vlf.fbs.StartEvent;
import com.google.flatbuffers.FlatBufferBuilder;

public final class VlfJournal implements Journal
{
    private static final Logger logger = LoggerFactory.getLogger(VlfJournal.class);
    private static final long OS_PAGE_SIZE = 4096L;
    private static final ValueLayout.OfInt INT_BE = JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfShort SHORT_BE = JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final int MAX_SCRATCH = 8192;
    private static final int MAGIC = 0x564C4631; // "VLF1"
    private final FlatBufferBuilder fbb = new FlatBufferBuilder(8192);
    // Keep these at the class level to prevent allocation!
    private final byte[] asciiScratch = new byte[MAX_SCRATCH];
    private final int[] headerOffsetsScratch = new int[1024];
    private final int[] attributeOffsetsScratch = new int[100];
    private final VlfJournalProvider provider;
    private final Consumer<Path> finishedJournalFileSupplier;
    byte[] journalLevels = new byte[]{
            com.ethlo.venturi.vlf.fbs.JournalLevel.NONE,
            com.ethlo.venturi.vlf.fbs.JournalLevel.METADATA,
            com.ethlo.venturi.vlf.fbs.JournalLevel.HEADERS,
            com.ethlo.venturi.vlf.fbs.JournalLevel.FULL,
    };
    private int currentAttributeCount = 0;
    private int currentHeaderCount = 0;
    private FileChannel channel;
    private Arena arena;
    private MemorySegment segment;
    private long position;
    private Path activePath;

    public VlfJournal(VlfJournalProvider provider, final Consumer<Path> finishedJournalFileSupplier)
    {
        this.provider = provider;
        this.finishedJournalFileSupplier = finishedJournalFileSupplier;
        rotateSegment();
    }

    public VlfJournal(VlfJournalProvider provider, int segmentSize)
    {
        this(provider, null);
    }

    private static void updateInt(CRC32C crc, int value)
    {
        crc.update((value >>> 24) & 0xFF);
        crc.update((value >>> 16) & 0xFF);
        crc.update((value >>> 8) & 0xFF);
        crc.update(value & 0xFF);
    }

    /* =======================
       PUBLIC API
       ======================= */

    // The restored and slightly JIT-optimized scratch copier
    private int copyToScratch(CharSequence s)
    {
        final int len = s.length();
        if (len > MAX_SCRATCH)
        {
            throw new IllegalArgumentException("String exceeds max scratch size: " + len);
        }

        final byte[] dest = this.asciiScratch;

        // JIT optimization: Devirtualizes the charAt call if it's a standard String
        if (s instanceof String str)
        {
            for (int i = 0; i < len; i++)
            {
                dest[i] = (byte) str.charAt(i);
            }
        }
        else
        {
            for (int i = 0; i < len; i++)
            {
                dest[i] = (byte) s.charAt(i);
            }
        }
        return len;
    }

    @Override
    public synchronized void start(ServerDirection dir, JournalLevel journalLevel, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers)
    {
        fbb.clear();

        int reqIdOffset = fbb.createByteVector(asciiScratch, 0, copyToScratch(reqId));
        int startLineOffset = fbb.createByteVector(startLine);
        this.currentHeaderCount = 0;

        // Pass 'this' as state to avoid capturing lambda allocation
        headers.forEach(this, (self, name, value) ->
                {
                    writeFlatBufferHeader(self, name, value);
                    self.headerOffsetsScratch[self.currentHeaderCount++] = Header.endHeader(self.fbb);
                }
        );

        int headerCount = this.currentHeaderCount;
        StartEvent.startHeadersVector(fbb, headerCount);
        for (int i = headerCount - 1; i >= 0; i--)
        {
            fbb.addOffset(headerOffsetsScratch[i]);
        }
        int headersVectorOffset = fbb.endVector();

        StartEvent.startStartEvent(fbb);
        StartEvent.addJournalLevel(fbb, journalLevels[journalLevel.ordinal()]);
        StartEvent.addReqId(fbb, reqIdOffset);
        StartEvent.addDirection(fbb, (byte) dir.ordinal());
        StartEvent.addStartLine(fbb, startLineOffset);
        StartEvent.addHeaders(fbb, headersVectorOffset);
        int startEventOffset = StartEvent.endStartEvent(fbb);

        JournalEvent.startJournalEvent(fbb);
        JournalEvent.addEventType(fbb, EventPayload.StartEvent);
        JournalEvent.addEvent(fbb, startEventOffset);
        int rootOffset = JournalEvent.endJournalEvent(fbb);

        fbb.finish(rootOffset);
        writeEntry(fbb, null);
    }

    @Override
    public synchronized void body(ServerDirection dir, CharSequence reqId, ByteBuffer data)
    {
        while (data.hasRemaining())
        {
            fbb.clear();
            int reqIdOffset = fbb.createByteVector(asciiScratch, 0, copyToScratch(reqId));

            BodyEvent.startBodyEvent(fbb);
            BodyEvent.addReqId(fbb, reqIdOffset);
            BodyEvent.addDirection(fbb, dir == ServerDirection.REQUEST ?
                    com.ethlo.venturi.vlf.fbs.ServerDirection.REQUEST :
                    com.ethlo.venturi.vlf.fbs.ServerDirection.RESPONSE
            );
            BodyEvent.addLength(fbb, data.remaining());
            int bodyEventOffset = BodyEvent.endBodyEvent(fbb);
            JournalEvent.startJournalEvent(fbb);
            JournalEvent.addEventType(fbb, EventPayload.BodyEvent);
            JournalEvent.addEvent(fbb, bodyEventOffset);
            int rootOffset = JournalEvent.endJournalEvent(fbb);

            fbb.finish(rootOffset);
            writeEntry(fbb, data);
        }
    }

    /* =======================
       ZERO-COPY WRITE
       ======================= */

    @Override
    public synchronized void end(CharSequence reqId, GatewayAttributes attributes, int status, long sent, long recv, long duration)
    {
        fbb.clear();
        final int reqIdOffset = fbb.createByteVector(asciiScratch, 0, copyToScratch(reqId));

        // 1. Process Attributes using zero-allocation iteration
        this.currentAttributeCount = 0;
        if (attributes != null)
        {
            attributes.forEach(this, (self, name, value) ->
                    {
                        writeFlatBufferHeader(self, name, value);
                        self.attributeOffsetsScratch[self.currentAttributeCount++] = Header.endHeader(self.fbb);
                    }
            );
        }

        int attributeCount = this.currentAttributeCount;
        EndEvent.startAttributesVector(fbb, attributeCount);
        for (int i = attributeCount - 1; i >= 0; i--)
        {
            fbb.addOffset(attributeOffsetsScratch[i]);
        }
        int attributesVectorOffset = fbb.endVector();

        EndEvent.startEndEvent(fbb);
        EndEvent.addReqId(fbb, reqIdOffset);
        EndEvent.addTimestamp(fbb, System.currentTimeMillis());
        EndEvent.addStatus(fbb, status);
        EndEvent.addBytesSent(fbb, sent);
        EndEvent.addBytesReceived(fbb, recv);
        EndEvent.addDuration(fbb, duration);
        EndEvent.addAttributes(fbb, attributesVectorOffset);
        final int endEventOffset = EndEvent.endEndEvent(fbb);

        JournalEvent.startJournalEvent(fbb);
        JournalEvent.addEventType(fbb, EventPayload.EndEvent);
        JournalEvent.addEvent(fbb, endEventOffset);
        int rootOffset = JournalEvent.endJournalEvent(fbb);

        fbb.finish(rootOffset);
        writeEntry(fbb, null);
    }

    private void writeFlatBufferHeader(final VlfJournal self, final CharSequence name, final CharSequence value)
    {
        int nameOffset = self.fbb.createByteVector(self.asciiScratch, 0, self.copyToScratch(name));
        int valueOffset = self.fbb.createByteVector(self.asciiScratch, 0, self.copyToScratch(value));

        Header.startHeader(self.fbb);
        Header.addName(self.fbb, nameOffset);
        Header.addValue(self.fbb, valueOffset);
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

        final long entryStart = position;

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
        putInt(MAGIC);
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
        final long capacity = segment.byteSize();
        for (long offset = 0; offset < capacity; offset += OS_PAGE_SIZE)
        {
            segment.set(JAVA_BYTE, offset, (byte) 0);
        }

        if (capacity > 0)
        {
            segment.set(JAVA_BYTE, capacity - 1, (byte) 0);
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
        // For now, using System.currentTimeMillis() as a simple chronological fallback
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