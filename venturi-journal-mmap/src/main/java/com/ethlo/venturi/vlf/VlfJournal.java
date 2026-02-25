package com.ethlo.venturi.vlf;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.ServerDirection;
import com.ethlo.venturi.journal.api.Journal;
import com.ethlo.venturi.vlf.fbs.BodyEvent;
import com.ethlo.venturi.vlf.fbs.EndEvent;
import com.ethlo.venturi.vlf.fbs.EventPayload;
import com.ethlo.venturi.vlf.fbs.Header;
import com.ethlo.venturi.vlf.fbs.JournalEvent;
import com.ethlo.venturi.vlf.fbs.StartEvent;
import com.google.flatbuffers.FlatBufferBuilder;

public final class VlfJournal implements Journal
{
    private static final long OS_PAGE_SIZE = 4096L;
    private static final ValueLayout.OfInt INT_BE = JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfShort SHORT_BE = JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final int MAX_SCRATCH = 8192;
    private static final int MAGIC = 0x564C4631; // "VLF1"
    private final FlatBufferBuilder fbb = new FlatBufferBuilder(8192);
    private final byte[] asciiScratch = new byte[MAX_SCRATCH];

    private final VlfJournalProvider provider;
    private final long segmentSize;
    private FileChannel channel;
    private Arena arena;
    private MemorySegment segment;
    private long position;
    private Path activePath;

    /* =======================
       PUBLIC API
       ======================= */

    public VlfJournal(VlfJournalProvider provider, long segmentSize)
    {
        this.provider = provider;
        this.segmentSize = segmentSize;
        rotateData();
    }

    private static void updateInt(CRC32C crc, int value)
    {
        crc.update((value >>> 24) & 0xFF);
        crc.update((value >>> 16) & 0xFF);
        crc.update((value >>> 8) & 0xFF);
        crc.update(value & 0xFF);
    }

    @Override
    public synchronized void start(ServerDirection dir, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers)
    {
        fbb.clear();

        int reqIdOffset = fbb.createByteVector(asciiScratch, 0, copyToScratch(reqId));
        int startLineOffset = fbb.createByteVector(startLine);

        final int[] headerOffsetsScratch = new int[100];
        int[] index = {0};
        headers.forEach((name, value) -> {
            int nameOffset = fbb.createByteVector(asciiScratch, 0, copyToScratch(name));
            int valueOffset = fbb.createByteVector(asciiScratch, 0, copyToScratch(value));

            Header.startHeader(fbb);
            Header.addName(fbb, nameOffset);
            Header.addValue(fbb, valueOffset);
            headerOffsetsScratch[index[0]++] = Header.endHeader(fbb);
        });

        int headerCount = index[0];
        StartEvent.startHeadersVector(fbb, headerCount);
        for (int i = headerCount - 1; i >= 0; i--)
        {
            fbb.addOffset(headerOffsetsScratch[i]);
        }
        int headersVectorOffset = fbb.endVector();

        StartEvent.startStartEvent(fbb);
        StartEvent.addReqId(fbb, reqIdOffset);
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
    public synchronized void end(CharSequence reqId, int status, long sent, long recv, long duration)
    {
        fbb.clear();
        final int reqIdOffset = fbb.createByteVector(asciiScratch, 0, copyToScratch(reqId));

        EndEvent.startEndEvent(fbb);
        EndEvent.addReqId(fbb, reqIdOffset);
        EndEvent.addTimestamp(fbb, System.currentTimeMillis());
        EndEvent.addStatus(fbb, status);
        EndEvent.addBytesSent(fbb, sent);
        EndEvent.addBytesReceived(fbb, recv);
        EndEvent.addDuration(fbb, duration);
        final int endEventOffset = EndEvent.endEndEvent(fbb);

        JournalEvent.startJournalEvent(fbb);
        JournalEvent.addEventType(fbb, EventPayload.EndEvent);
        JournalEvent.addEvent(fbb, endEventOffset);
        int rootOffset = JournalEvent.endJournalEvent(fbb);

        fbb.finish(rootOffset);
        writeEntry(fbb, null);
    }

    @Override
    public synchronized void close() throws IOException
    {
        if (segment != null)
        {
            segment.force();
            arena.close();
            String newName = activePath.getFileName().toString().replace(".active", VlfConstants.VLF_EXTENSION);
            Files.move(activePath, activePath.resolveSibling(newName), StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private void ensureCapacity(long needed)
    {
        if (segment == null)
        {
            rotateData();
        }

        if (position + needed > segment.byteSize())
        {
            rotateData();
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

        final long entryStart = position;

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

    /* =======================
       HELPERS
       ======================= */

    private int copyToScratch(CharSequence s)
    {
        int len = s.length();
        if (len > MAX_SCRATCH) throw new IllegalArgumentException("String exceeds max scratch size: " + len);
        for (int i = 0; i < len; i++) asciiScratch[i] = (byte) s.charAt(i);
        return len;
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

    private void rotateData()
    {
        try
        {
            if (segment != null)
            {
                segment.force();
                arena.close();
                channel.close();

                String newName = activePath.getFileName()
                        .toString().replace(".active", VlfConstants.VLF_EXTENSION);

                Files.move(activePath,
                        activePath.resolveSibling(newName),
                        StandardCopyOption.ATOMIC_MOVE
                );
            }

            activePath = provider.getNextPath();

            channel = FileChannel.open(
                    activePath,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE
            );

            channel.truncate(segmentSize);

            arena = Arena.ofShared();
            segment = channel.map(
                    FileChannel.MapMode.READ_WRITE,
                    0,
                    segmentSize,
                    arena
            );

            preFaultSegment(segment);
            position = 0;
            writePreamble();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
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