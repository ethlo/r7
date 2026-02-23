package com.ethlo.venturi.vlf;

import static com.ethlo.venturi.vlf.VlfConstants.LONG_STRING_LENGTH_BOUNDARY;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Properties;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.ServerDirection;
import com.ethlo.venturi.journal.api.Journal;
import com.ethlo.venturi.util.HttpStringCharSequence;
import com.ethlo.venturi.vlf.dictionary.VlfDictionary;
import com.ethlo.venturi.vlf.dictionary.VlfDictionaryByteUltra;

public final class VlfJournal implements Journal
{
    // Standard OS page size is almost universally 4KB
    private static final long OS_PAGE_SIZE = 4096L;
    private static final ValueLayout.OfInt INT_BE =
            JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfShort SHORT_BE =
            JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong LONG_BE =
            JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final int MAX_SCRATCH = 8192;
    public final int maxHeaderBytes = 4096;
    private final VlfJournalProvider provider;
    private final VlfDictionary dictionary;
    private final long segmentSize;
    private final long indexSize;
    private final boolean isWriteIndex = false;
    private final byte[] asciiScratch = new byte[MAX_SCRATCH];
    private final MemorySegment asciiScratchSegment = MemorySegment.ofArray(asciiScratch);
    private Arena arena;
    private MemorySegment segment;
    private long position;
    private IndexSegment index;
    private Path activePath;
    private Path activeIndexPath;
    private int currentFileId = 0;

    public VlfJournal(VlfJournalProvider provider,
                      VlfDictionary dictionary,
                      long segmentSize,
                      long indexSize)
    {
        this.provider = provider;
        this.dictionary = dictionary;
        this.segmentSize = segmentSize;
        this.indexSize = indexSize;

        rotateData(null);
        rotateIndex();
    }

    public VlfJournal(VlfJournalProvider provider,
                      long segmentSize,
                      long indexSize)
    {
        this(provider,
                load("default-dict.properties"),
                segmentSize,
                indexSize
        );
    }

    /**
     * Static loader for the Writer to initialize from classpath
     */
    public static VlfDictionary load(String classPath)
    {
        Properties props = new Properties();
        try (InputStream in = VlfDictionary.class.getResourceAsStream(classPath.startsWith("/") ? classPath : "/" + classPath))
        {
            if (in == null)
            {
                throw new IOException("No resource found for classpath " + classPath);
            }
            props.load(in);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        return new VlfDictionaryByteUltra(props);
    }

    /**
     * Forces the OS to physically allocate disk blocks for the entire mapped segment.
     * Prevents sparse file creation and runtime SIGBUS/InternalError crashes.
     */
    private void preFaultSegment(MemorySegment segment) throws InternalError
    {
        final long capacity = segment.byteSize();

        // Stride through the segment, touching one byte per 4KB page
        for (long offset = 0; offset < capacity; offset += OS_PAGE_SIZE)
        {
            segment.set(ValueLayout.JAVA_BYTE, offset, (byte) 0);
        }

        // Guarantee the absolute last byte is also physically backed
        // in case the segment size is not a perfect multiple of 4096
        if (capacity > 0)
        {
            segment.set(ValueLayout.JAVA_BYTE, capacity - 1, (byte) 0);
        }
    }


    /* =======================
       Primitive Writers
       ======================= */

    private void putByte(byte v)
    {
        segment.set(JAVA_BYTE, position, v);
        position += 1;
    }

    private void putShort(short v)
    {
        segment.set(SHORT_BE, position, v);
        position += Short.BYTES;
    }

    private void putInt(int v)
    {
        segment.set(INT_BE, position, v);
        position += Integer.BYTES;
    }

    private void putLong(long v)
    {
        segment.set(LONG_BE, position, v);
        position += Long.BYTES;
    }

    private long remaining()
    {
        return segment.byteSize() - position;
    }

    /* =======================
       ASCII Writer (Zero Allocation)
       ======================= */

    private int writeAsciiFast(CharSequence s)
    {
        int len = s.length();

        if (len > MAX_SCRATCH)
        {
            return writeAsciiSlow(s);
        }

        for (int i = 0; i < len; i++)
        {
            char c = s.charAt(i);
            if (c > 0x7F)
            {
                throw new UncheckedIOException(new IOException("Non ASCII: " + c));
            }
            asciiScratch[i] = (byte) c;
        }

        MemorySegment.copy(
                asciiScratchSegment,
                0,
                segment,
                position,
                len
        );

        position += len;
        return len;
    }

    private int writeAsciiSlow(CharSequence s)
    {
        int len = s.length();

        for (int i = 0; i < len; i++)
        {
            char c = s.charAt(i);
            if (c > 0x7F)
                throw new UncheckedIOException(
                        new IOException("Non ASCII: " + c));
            segment.set(JAVA_BYTE, position + i, (byte) c);
        }

        position += len;
        return len;
    }

    /* =======================
       Journal API
       ======================= */

    @Override
    public synchronized void start(ServerDirection dir,
                                   CharSequence reqId,
                                   ByteBuffer startLine,
                                   GatewayHeaders headers)
    {
        try
        {
            ensureIndexCapacity();
            ensureCapacity(maxHeaderBytes + reqId.length() + startLine.remaining(), reqId);

            final int fileIdAtWrite = currentFileId;
            final long startOffset = position;

            writeEntryHeader(VlfConstants.MARKER_START, reqId);
            putByte((byte) dir.ordinal());
            writePrefixedBuffer(startLine);

            long countPos = position;
            putInt(0);


            final int count = headers.forEach(this, (journal, name, header) ->
                    {
                        journal.writeStringWithDict(name);
                        journal.writeStringWithDict(header);
                    }
            );

            segment.set(INT_BE, countPos, count);

            if (isWriteIndex)
                index.record(reqId, fileIdAtWrite, startOffset);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void body(ServerDirection dir,
                                  CharSequence reqId,
                                  ByteBuffer data)
    {
        try
        {
            while (data.hasRemaining())
            {
                ensureIndexCapacity();

                int minRequired = 16 + reqId.length();
                if (remaining() < minRequired + 1)
                    rotateData(reqId);

                final int fileIdAtWrite = currentFileId;
                final long startOffset = position;

                int available = (int) (remaining() - minRequired);
                int toWrite = Math.min(data.remaining(), available);

                putByte(VlfConstants.MARKER_BODY);
                putByte((byte) reqId.length());
                writeAsciiFast(reqId);
                putByte((byte) dir.ordinal());
                putInt(toWrite);

                writeBufferSlice(data, toWrite);

                if (isWriteIndex)
                    index.record(reqId, fileIdAtWrite, startOffset);
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void end(CharSequence reqId,
                                 int status,
                                 long sent,
                                 long recv,
                                 long duration)
    {
        try
        {
            ensureIndexCapacity();
            ensureCapacity(128 + reqId.length(), reqId);

            final int fileIdAtWrite = currentFileId;
            final long startOffset = position;

            writeEntryHeader(VlfConstants.MARKER_END, reqId);
            putLong(System.currentTimeMillis());
            putInt(status);
            putLong(sent);
            putLong(recv);
            putLong(duration);

            if (isWriteIndex)
                index.record(reqId, fileIdAtWrite, startOffset);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    /* =======================
       Buffer Writers
       ======================= */

    private void writePrefixedBuffer(ByteBuffer src)
    {
        putInt(src.remaining());
        writeBuffer(src);
    }

    private void writeBuffer(ByteBuffer src)
    {
        int len = src.remaining();
        writeBufferSlice(src, len);
    }

    private void writeBufferSlice(ByteBuffer src, int length)
    {
        if (src.isDirect())
        {
            MemorySegment srcSeg = MemorySegment.ofBuffer(src);
            MemorySegment.copy(srcSeg, src.position(), segment, position, length);
        }
        else if (src.hasArray())
        {
            byte[] arr = src.array();
            int off = src.arrayOffset() + src.position();
            MemorySegment.copy(
                    MemorySegment.ofArray(arr),
                    off,
                    segment,
                    position,
                    length
            );
        }
        else
        {
            MemorySegment srcSeg = MemorySegment.ofBuffer(src);
            MemorySegment.copy(srcSeg, src.position(), segment, position, length);
        }

        src.position(src.position() + length);
        position += length;
    }

    /* =======================
       Entry + Dictionary
       ======================= */

    private void writeEntryHeader(byte marker, CharSequence reqId)
    {
        putByte(marker);
        putByte((byte) reqId.length());
        writeAsciiFast(reqId);
    }

    private void writeStringWithDict(CharSequence s)
    {
        if (s == null)
        {
            putByte(VlfConstants.NULL_VALUE);
            return;
        }

        byte id = -1;
        if (s instanceof HttpStringCharSequence httpStringCharSequence)
        {
            id = dictionary.encode(httpStringCharSequence.getBytes());
        }

        if (id != -1)
        {
            putByte(VlfConstants.DICT_LOOKUP);
            putByte(id);
        }
        else
        {
            int len = s.length();
            if (len >= LONG_STRING_LENGTH_BOUNDARY)
            {
                putByte(VlfConstants.LONG_STRING);
                long lengthPos = position;
                putInt(0);

                int bytesWritten = writeAsciiFast(s);
                segment.set(INT_BE, lengthPos, bytesWritten);
            }
            else
            {
                long lengthPos = position;
                putByte((byte) 0);

                int bytesWritten = writeAsciiFast(s);
                segment.set(JAVA_BYTE, lengthPos, (byte) bytesWritten);
            }
        }
    }

    /* =======================
       Rotation
       ======================= */

    private void rotateData(CharSequence triggeringReqId)
    {
        try
        {
            if (segment != null)
            {
                segment.force();
                arena.close();

                String newName = activePath.getFileName()
                        .toString()
                        .replace(".active", ".raw");

                Files.move(activePath,
                        activePath.resolveSibling(newName),
                        StandardCopyOption.ATOMIC_MOVE
                );
            }

            activePath = provider.getNextPath();
            currentFileId = provider.getRotationCount();

            try (FileChannel fc = FileChannel.open(
                    activePath,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE
            ))
            {
                fc.truncate(segmentSize);

                arena = Arena.ofShared();
                segment = fc.map(
                        FileChannel.MapMode.READ_WRITE,
                        0,
                        segmentSize,
                        arena
                );

                try
                {
                    preFaultSegment(segment);
                }
                catch (InternalError e)
                {
                    throw new UncheckedIOException(new IOException("Unable to allocate journal segment " + activePath + " of size " + segmentSize + " bytes. Is there enough disk space?", e));
                }

                position = 0;
                writePreamble();
            }
        }
        catch (IOException exc)
        {
            throw new UncheckedIOException(exc);
        }
    }

    private void rotateIndex()
    {
        try
        {
            if (index != null)
            {
                index.close();
                String newName = activeIndexPath.getFileName()
                        .toString()
                        .replace(".active", ".raw");

                Files.move(activeIndexPath,
                        activeIndexPath.resolveSibling(newName),
                        StandardCopyOption.ATOMIC_MOVE
                );
            }

            String dataFileName = activePath.getFileName().toString();
            String baseName = dataFileName.contains(".")
                    ? dataFileName.substring(0, dataFileName.lastIndexOf('.'))
                    : dataFileName;

            activeIndexPath = activePath.resolveSibling(baseName + ".index");

            if (isWriteIndex)
                index = new IndexSegment(activeIndexPath, indexSize);
        }
        catch (IOException exc)
        {
            throw new UncheckedIOException("Unable to rotate index", exc);
        }
    }

    private void writePreamble()
    {
        position = 0;

        putInt(VlfConstants.MAGIC);
        putShort(VlfConstants.VERSION_1);

        Map<CharSequence, Byte> entries = dictionary.getEntries();
        putShort((short) entries.size());

        for (Map.Entry<CharSequence, Byte> entry : entries.entrySet())
        {
            byte[] b = entry.getKey().toString().getBytes(StandardCharsets.UTF_8);
            putByte(entry.getValue());
            putByte((byte) b.length);
            MemorySegment.copy(
                    MemorySegment.ofArray(b),
                    0,
                    segment,
                    position,
                    b.length
            );
            position += b.length;
        }

        if (position > VlfConstants.PREAMBLE_SIZE)
            throw new IllegalStateException("Dictionary too large");

        position = VlfConstants.PREAMBLE_SIZE;
    }

    private void ensureCapacity(long needed,
                                CharSequence reqId) throws IOException
    {
        if (needed > segmentSize - VlfConstants.PREAMBLE_SIZE)
            throw new IllegalArgumentException("Entry too large");

        if (remaining() < needed)
            rotateData(reqId);
    }

    private void ensureIndexCapacity() throws IOException
    {
        if (index != null && !index.hasSpace())
            rotateIndex();
    }

    @Override
    public synchronized void close() throws IOException
    {
        if (segment != null)
        {
            segment.force();
            arena.close();

            String newName = activePath.getFileName()
                    .toString()
                    .replace(".active", ".raw");

            Files.move(activePath,
                    activePath.resolveSibling(newName),
                    StandardCopyOption.ATOMIC_MOVE
            );
        }

        if (index != null)
        {
            index.close();

            String newName = activeIndexPath.getFileName()
                    .toString()
                    .replace(".active", ".raw");

            Files.move(activeIndexPath,
                    activeIndexPath.resolveSibling(newName),
                    StandardCopyOption.ATOMIC_MOVE
            );
        }
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