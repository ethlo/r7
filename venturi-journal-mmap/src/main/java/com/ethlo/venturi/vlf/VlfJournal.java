package com.ethlo.venturi.vlf;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
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

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.ServerDirection;
import com.ethlo.venturi.journal.api.Journal;
import com.ethlo.venturi.vlf.fbs.BodyEvent;
import com.ethlo.venturi.vlf.fbs.EndEvent;
import com.ethlo.venturi.vlf.fbs.Header;
import com.ethlo.venturi.vlf.fbs.StartEvent;
import com.google.flatbuffers.FlatBufferBuilder;

public final class VlfJournal implements Journal {

    private static final long OS_PAGE_SIZE = 4096L;
    private static final ValueLayout.OfInt INT_BE = JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfShort SHORT_BE = JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    private static final int MAX_SCRATCH = 8192;

    private final FlatBufferBuilder fbb = new FlatBufferBuilder(8192);
    private final byte[] asciiScratch = new byte[MAX_SCRATCH];

    private final VlfJournalProvider provider;
    private final long segmentSize;

    private Arena arena;
    private MemorySegment segment;
    private long position;
    private Path activePath;

    public VlfJournal(VlfJournalProvider provider, long segmentSize) {
        this.provider = provider;
        this.segmentSize = segmentSize;
        rotateData(null);
    }

    /* =======================
       PUBLIC API
       ======================= */

    @Override
    public synchronized void start(ServerDirection dir, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers) {
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
        for (int i = headerCount - 1; i >= 0; i--) {
            fbb.addOffset(headerOffsetsScratch[i]);
        }
        int headersVectorOffset = fbb.endVector();

        StartEvent.startStartEvent(fbb);
        StartEvent.addReqId(fbb, reqIdOffset);
        StartEvent.addStartLine(fbb, startLineOffset);
        StartEvent.addHeaders(fbb, headersVectorOffset);
        int startEventOffset = StartEvent.endStartEvent(fbb);

        fbb.finish(startEventOffset);

        writeFramedFlatBufferSafe(VlfConstants.MARKER_START, fbb, null);
    }

    @Override
    public synchronized void body(ServerDirection dir, CharSequence reqId, ByteBuffer data) {
        while (data.hasRemaining()) {
            fbb.clear();
            int reqIdOffset = fbb.createByteVector(asciiScratch, 0, copyToScratch(reqId));

            BodyEvent.startBodyEvent(fbb);
            BodyEvent.addReqId(fbb, reqIdOffset);
            BodyEvent.addDirection(fbb, dir == ServerDirection.REQUEST ?
                    com.ethlo.venturi.vlf.fbs.ServerDirection.REQUEST :
                    com.ethlo.venturi.vlf.fbs.ServerDirection.RESPONSE);
            BodyEvent.addLength(fbb, data.remaining());
            int bodyEventOffset = BodyEvent.endBodyEvent(fbb);

            fbb.finish(bodyEventOffset);

            // Write FlatBuffer + raw body bytes
            writeFramedFlatBufferSafe(VlfConstants.MARKER_BODY, fbb, data);
        }
    }

    @Override
    public synchronized void end(CharSequence reqId, int status, long sent, long recv, long duration) {
        fbb.clear();
        int reqIdOffset = fbb.createByteVector(asciiScratch, 0, copyToScratch(reqId));

        EndEvent.startEndEvent(fbb);
        EndEvent.addReqId(fbb, reqIdOffset);
        EndEvent.addTimestamp(fbb, System.currentTimeMillis());
        EndEvent.addStatus(fbb, status);
        EndEvent.addBytesSent(fbb, sent);
        EndEvent.addBytesReceived(fbb, recv);
        EndEvent.addDuration(fbb, duration);
        int endEventOffset = EndEvent.endEndEvent(fbb);

        fbb.finish(endEventOffset);

        writeFramedFlatBufferSafe(VlfConstants.MARKER_END, fbb, null);
    }

    @Override
    public synchronized void close() throws IOException {
        if (segment != null) {
            segment.force();
            arena.close();
            String newName = activePath.getFileName().toString().replace(".active", ".raw");
            Files.move(activePath, activePath.resolveSibling(newName), StandardCopyOption.ATOMIC_MOVE);
        }
    }

    /* =======================
       ZERO-COPY WRITE
       ======================= */

    private void writeFramedFlatBufferSafe(byte marker, FlatBufferBuilder fbBuilder, ByteBuffer rawData) {
        ByteBuffer fbData = fbBuilder.dataBuffer().slice(); // ensure position=0, limit=actual FB
        int fbLen = fbData.remaining();
        int rawLen = (rawData != null) ? rawData.remaining() : 0;

        long totalNeeded = 1L + Integer.BYTES + fbLen + rawLen;
        if (totalNeeded > remaining()) {
            rotateData(null);
            if (totalNeeded > remaining()) {
                throw new UncheckedIOException(new IOException("Entry too large for journal segment even after rotation"));
            }
        }

        // 1. Marker
        segment.set(JAVA_BYTE, position, marker);
        position += 1;

        // 2. FB length
        segment.set(INT_BE, position, fbLen);
        position += Integer.BYTES;

        // 3. FB bytes
        MemorySegment fbSeg = MemorySegment.ofArray(fbData.array());
        MemorySegment.copy(fbSeg, fbData.arrayOffset() + fbData.position(), segment, position, fbLen);
        position += fbLen;

        // 4. Raw payload
        if (rawLen > 0) {
            MemorySegment rawSeg = MemorySegment.ofBuffer(rawData);
            MemorySegment.copy(rawSeg, rawData.position(), segment, position, rawLen);
            rawData.position(rawData.position() + rawLen);
            position += rawLen;
        }
    }

    /* =======================
       HELPERS
       ======================= */

    private int copyToScratch(CharSequence s) {
        int len = s.length();
        if (len > MAX_SCRATCH) throw new IllegalArgumentException("String exceeds max scratch size: " + len);
        for (int i = 0; i < len; i++) asciiScratch[i] = (byte) s.charAt(i);
        return len;
    }

    private void preFaultSegment(MemorySegment segment) {
        final long capacity = segment.byteSize();
        for (long offset = 0; offset < capacity; offset += OS_PAGE_SIZE) segment.set(JAVA_BYTE, offset, (byte) 0);
        if (capacity > 0) segment.set(JAVA_BYTE, capacity - 1, (byte) 0);
    }

    private long remaining() {
        return segment.byteSize() - position;
    }

    private void rotateData(CharSequence triggeringReqId) {
        try {
            if (segment != null) {
                segment.force();
                arena.close();
                String newName = activePath.getFileName().toString().replace(".active", ".raw");
                Files.move(activePath, activePath.resolveSibling(newName), StandardCopyOption.ATOMIC_MOVE);
            }

            activePath = provider.getNextPath();

            try (FileChannel fc = FileChannel.open(activePath, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
                fc.truncate(segmentSize);
                arena = Arena.ofShared();
                segment = fc.map(FileChannel.MapMode.READ_WRITE, 0, segmentSize, arena);
                preFaultSegment(segment);
                position = 0;
                writePreamble();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writePreamble() {
        position = 0;
        putInt(VlfConstants.MAGIC);
        putShort(VlfConstants.VERSION_1);
        position = VlfConstants.PREAMBLE_SIZE;
    }

    private void putByte(byte v) {
        segment.set(JAVA_BYTE, position, v);
        position += 1;
    }

    private void putInt(int v) {
        segment.set(INT_BE, position, v);
        position += Integer.BYTES;
    }

    private void putShort(short v) {
        segment.set(SHORT_BE, position, v);
        position += Short.BYTES;
    }

    public Path getActivePath() {
        return activePath;
    }

    public long getOffset() {
        return position;
    }
}