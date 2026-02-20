package com.ethlo.venturi.vlf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.ServerDirection;
import com.ethlo.venturi.auditing.api.Journal;

/**
 * High-performance binary journal using Memory Mapped Files (mmap),
 * Sharding, and Dictionary Compression.
 */
public final class VlfJournal implements Journal
{
    private static final Logger logger = LoggerFactory.getLogger(VlfJournal.class);

    private final VlfJournalProvider provider;
    private final VlfDictionary dictionary;
    private final long segmentSize;
    private final long indexSize;

    private MappedByteBuffer buffer;
    private IndexSegment index;
    private Path activePath;
    private Path activeIndexPath;
    private int currentFileId = 0;

    // TODO: Make configurable
    private boolean isWriteIndex = false;

    public VlfJournal(VlfJournalProvider provider, VlfDictionary dictionary, long segmentSize, long indexSize)
    {
        this.provider = provider;
        this.dictionary = dictionary;
        this.segmentSize = segmentSize;
        this.indexSize = indexSize;
        this.rotateData(null);
        this.rotateIndex();
    }

    public VlfJournal(VlfJournalProvider provider, long segmentSize, long indexSize)
    {
        this(provider, VlfDictionary.load("default-dict.properties"), segmentSize, indexSize);
    }

    public static void unmap(MappedByteBuffer bb)
    {
        if (bb == null || !bb.isDirect()) return;

        try
        {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            java.lang.reflect.Method invokeCleaner = unsafeClass.getMethod("invokeCleaner", java.nio.ByteBuffer.class);
            invokeCleaner.invoke(unsafe, bb);
        }
        catch (Exception e)
        {
            logger.debug("Manual unmap failed: {}", e.getMessage());
        }
    }

    @Override
    public synchronized void start(ServerDirection dir, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers)
    {
        try
        {
            ensureIndexCapacity();
            // Estimate capacity: Header(1) + ReqID + Dir(1) + StartLine + HeaderCount(4) + approx header space
            ensureCapacity(512 + reqId.length() + startLine.remaining(), reqId);

            final int fileIdAtWrite = this.currentFileId;
            final long startOffset = buffer.position();

            writeEntryHeader(VlfConstants.MARKER_START, reqId);
            buffer.put((byte) dir.ordinal());
            writePrefixedBuffer(startLine);

            // Reserve space for header count, will fill later
            int countPos = buffer.position();
            buffer.putInt(0);

            final int count = headers.forEach((name, header) ->
            {
                writeStringWithDict(name);
                writeStringWithDict(header);
            });

            // Backpatch the actual header count
            buffer.putInt(countPos, count);

            if (isWriteIndex)
            {
                index.record(reqId, fileIdAtWrite, startOffset);
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void body(ServerDirection dir, CharSequence reqId, ByteBuffer data)
    {
        try
        {
            while (data.hasRemaining())
            {
                ensureIndexCapacity();

                // Minimum overhead for a body chunk
                int minRequired = 16 + reqId.length();
                if (buffer.remaining() < minRequired + 1)
                {
                    rotateData(reqId);
                }

                final int fileIdAtWrite = this.currentFileId;
                final long startOffset = buffer.position();

                int available = buffer.remaining() - minRequired;
                int toWrite = Math.min(data.remaining(), available);

                buffer.put(VlfConstants.MARKER_BODY);
                buffer.put((byte) reqId.length());
                writeRawCharSequence(reqId);
                buffer.put((byte) dir.ordinal());
                buffer.putInt(toWrite);

                ByteBuffer slice = data.duplicate();
                slice.limit(slice.position() + toWrite);
                buffer.put(slice);

                data.position(data.position() + toWrite);

                if (isWriteIndex)
                {
                    index.record(reqId, fileIdAtWrite, startOffset);
                }
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void end(CharSequence reqId, int status, long sent, long recv, long duration)
    {
        try
        {
            ensureIndexCapacity();
            ensureCapacity(128 + reqId.length(), reqId);

            final int fileIdAtWrite = this.currentFileId;
            final long startOffset = buffer.position();

            writeEntryHeader(VlfConstants.MARKER_END, reqId);
            buffer.putLong(System.currentTimeMillis());
            buffer.putInt(status);
            buffer.putLong(sent);
            buffer.putLong(recv);
            buffer.putLong(duration);

            if (isWriteIndex)
            {
                index.record(reqId, fileIdAtWrite, startOffset);
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    private void rotateData(CharSequence triggeringReqId)
    {
        try
        {
            if (buffer != null)
            {
                buffer.force();
                Path oldPath = activePath;
                unmap(buffer);
                buffer = null;

                String newName = oldPath.getFileName().toString().replace(".active", ".raw");
                Files.move(oldPath, oldPath.resolveSibling(newName), StandardCopyOption.ATOMIC_MOVE);
                logger.debug("ROTATION: Shard {} finalized. Triggered by: {}", newName, triggeringReqId);
            }

            this.activePath = provider.getNextPath();
            this.currentFileId = provider.getRotationCount();

            try (FileChannel fc = FileChannel.open(activePath, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE))
            {
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(activePath.toFile(), "rw"))
                {
                    raf.setLength(segmentSize);
                }
                this.buffer = fc.map(FileChannel.MapMode.READ_WRITE, 0, segmentSize);
                this.buffer.order(ByteOrder.BIG_ENDIAN);
                writePreamble();
            }
        }
        catch (IOException exc)
        {
            throw new UncheckedIOException(exc);
        }
    }

    private void writePreamble()
    {
        // Start at the very beginning
        buffer.position(0);

        // 1. Magic: 4 bytes (0, 1, 2, 3)
        buffer.putInt(VlfConstants.MAGIC);

        // 2. Version: 2 bytes (4, 5)
        buffer.putShort(VlfConstants.VERSION_1);

        // 3. Dictionary Entry Count: 2 bytes (6, 7)
        Map<String, Byte> entries = dictionary.getEntries();
        buffer.putShort((short) entries.size());

        // 4. Dictionary Content: (8+)
        for (Map.Entry<String, Byte> entry : entries.entrySet())
        {
            byte[] b = entry.getKey().getBytes(StandardCharsets.UTF_8);
            buffer.put(entry.getValue()); // ID (1 byte)
            buffer.put((byte) b.length);  // Length (1 byte)
            buffer.put(b);                // String bytes
        }

        // Safety check: Ensure we haven't exceeded our reserved space
        if (buffer.position() > VlfConstants.PREAMBLE_SIZE)
        {
            throw new IllegalStateException("Dictionary too large for 4KB preamble limit!");
        }

        // 5. Final Alignment: Jump to 4096 so data starts on a clean page boundary
        buffer.position(VlfConstants.PREAMBLE_SIZE);
    }

    private void writeStringWithDict(CharSequence s)
    {
        if (s == null)
        {
            buffer.put(VlfConstants.NULL_VALUE);
            return;
        }

        String str = s.toString();
        byte id = dictionary.encode(str);

        if (id != -1)
        {
            buffer.put(VlfConstants.DICT_LOOKUP);
            buffer.put(id);
        }
        else
        {
            byte[] b = str.getBytes(StandardCharsets.UTF_8);
            if (b.length >= 0xFE)
            {
                buffer.put(VlfConstants.LONG_STRING);
                buffer.putInt(b.length);
            }
            else
            {
                buffer.put((byte) b.length);
            }
            buffer.put(b);
        }
    }

    private void rotateIndex()
    {
        try
        {
            if (index != null)
            {
                index.close();
                Path oldPath = activeIndexPath;
                String newName = oldPath.getFileName().toString().replace(".active", ".raw");
                Files.move(oldPath, oldPath.resolveSibling(newName), StandardCopyOption.ATOMIC_MOVE);
            }

            String dataFileName = activePath.getFileName().toString();
            String baseName = dataFileName.contains(".")
                    ? dataFileName.substring(0, dataFileName.lastIndexOf('.'))
                    : dataFileName;

            this.activeIndexPath = activePath.resolveSibling(baseName + ".index");

            if (this.isWriteIndex)
            {
                this.index = new IndexSegment(activeIndexPath, indexSize);
                logger.debug("Index segment rotated to {}", activeIndexPath.getFileName());
            }
        }
        catch (IOException exc)
        {
            throw new UncheckedIOException("Unable to rotate index", exc);
        }
    }

    private void ensureCapacity(long needed, CharSequence reqId) throws IOException
    {
        if (needed > segmentSize - VlfConstants.PREAMBLE_SIZE)
        {
            throw new IllegalArgumentException("Entry too large for segment size");
        }
        if (buffer.remaining() < needed)
        {
            rotateData(reqId);
        }
    }

    private void ensureIndexCapacity() throws IOException
    {
        if (index != null)
        {
            if (!index.hasSpace())
            {
                rotateIndex();
            }
        }
    }

    private void writeEntryHeader(byte marker, CharSequence reqId)
    {
        buffer.put(marker);
        buffer.put((byte) reqId.length());
        writeRawCharSequence(reqId);
    }

    private void writeRawCharSequence(CharSequence cs)
    {
        for (int i = 0; i < cs.length(); i++)
        {
            buffer.put((byte) cs.charAt(i));
        }
    }

    private void writePrefixedBuffer(ByteBuffer src)
    {
        buffer.putInt(src.remaining());
        buffer.put(src.duplicate());
    }

    @Override
    public synchronized void close() throws IOException
    {
        if (buffer != null)
        {
            buffer.force();
            unmap(buffer);
            String newName = activePath.getFileName().toString().replace(".active", ".raw");
            Files.move(activePath, activePath.resolveSibling(newName), StandardCopyOption.ATOMIC_MOVE);
        }
        if (index != null)
        {
            index.close();
            String newName = activeIndexPath.getFileName().toString().replace(".active", ".raw");
            Files.move(activeIndexPath, activeIndexPath.resolveSibling(newName), StandardCopyOption.ATOMIC_MOVE);
        }
    }

    public Path getActivePath()
    {
        return activePath;
    }

    public long getOffset()
    {
        return buffer.position();
    }
}