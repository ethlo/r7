package com.ethlo.venturi.vlf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.ServerDirection;

public final class Journal implements AutoCloseable
{
    private static final Logger logger = LoggerFactory.getLogger(Journal.class);

    private final JournalProvider provider;
    private final long segmentSize;
    private final long indexSize;
    private final byte[] keyBuffer = new byte[256];

    private MappedByteBuffer buffer;
    private IndexSegment index;
    private Path activePath;
    private Path activeIndexPath;
    private int currentFileId = 0;

    public Journal(JournalProvider provider, long segmentSize, long indexSize) throws IOException
    {
        this.provider = provider;
        this.segmentSize = segmentSize;
        this.indexSize = indexSize;
        this.rotateData(null);
        this.rotateIndex();
    }

    public static void unmap(MappedByteBuffer bb)
    {
        if (bb == null || !bb.isDirect()) return;

        try
        {
            // Modern Java (9+) way to invoke the cleaner
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

    public synchronized void writeBegin(ServerDirection dir, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers)
    {
        try
        {
            ensureIndexCapacity();
            // Defensive: Marker(1) + ID(1+N) + Dir(4) + Line(4+N) + Count(4) + Headers
            ensureCapacity(512 + reqId.length() + startLine.remaining(), reqId);

            // FIX: Capture file identity AFTER potential rotation
            final int fileIdAtWrite = this.currentFileId;
            final long startOffset = buffer.position();

            writeEntryHeader(Marker.BEGIN, reqId);
            buffer.putInt(dir.ordinal());
            writePrefixedBuffer(startLine);

            int countPos = buffer.position();
            buffer.putInt(0);
            int[] count = {0};
            headers.forEach((k, v) ->
            {
                writeLowercasedHeaderName(k);
                writePrefixedString(v);
                count[0]++;
            });
            buffer.putInt(countPos, count[0]);

            index.record(reqId, fileIdAtWrite, startOffset);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void writeBodyPart(ServerDirection dir, CharSequence reqId, ByteBuffer data)
    {
        try
        {
            while (data.hasRemaining())
            {
                ensureIndexCapacity();

                int minRequired = 10 + reqId.length(); // Marker(1) + IdLen(1) + Id + Dir(4) + PayloadLen(4)
                if (buffer.remaining() < minRequired + 1)
                {
                    rotateData(reqId);
                }

                // Capture the state AFTER potential rotation to ensure the index
                // points to the file we are actually writing into.
                final int fileIdAtWrite = this.currentFileId;
                final long startOffset = buffer.position();

                int available = buffer.remaining() - minRequired;
                int toWrite = Math.min(data.remaining(), available);

                buffer.put(Marker.BODY);
                buffer.put((byte) reqId.length());
                writeRawCharSequence(reqId);
                buffer.putInt(dir.ordinal());
                buffer.putInt(toWrite);

                ByteBuffer slice = data.duplicate();
                slice.limit(slice.position() + toWrite);
                buffer.put(slice);

                data.position(data.position() + toWrite);

                // Link the index to the captured fileId
                index.record(reqId, fileIdAtWrite, startOffset);
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void writeEnd(CharSequence reqId, int status, long sent, long recv, long duration)
    {
        try
        {
            ensureIndexCapacity();
            ensureCapacity(128 + reqId.length(), reqId);

            // FIX: Capture file identity AFTER potential rotation
            final int fileIdAtWrite = this.currentFileId;
            final long startOffset = buffer.position();

            writeEntryHeader(Marker.END, reqId);
            buffer.putLong(System.currentTimeMillis());
            buffer.putInt(status);
            buffer.putLong(sent);
            buffer.putLong(recv);
            buffer.putLong(duration);

            index.record(reqId, fileIdAtWrite, startOffset);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    private void rotateData(CharSequence triggeringReqId) throws IOException
    {
        if (buffer != null)
        {
            buffer.force();
            Path oldPath = activePath;
            unmap(buffer);
            buffer = null;

            String newName = oldPath.getFileName().toString().replace(".active", ".raw");
            Files.move(oldPath, oldPath.resolveSibling(newName), StandardCopyOption.ATOMIC_MOVE);

            // LOG THIS: Now we know exactly which request forced the jump
            logger.debug("ROTATION: Shard {} moved to .raw. Triggered by ReqID: {}", oldPath.getFileName(), triggeringReqId);
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
            this.buffer.put(Marker.VERSION);
        }
    }

    private void rotateIndex() throws IOException
    {
        if (index != null)
        {
            index.close();
            Path oldPath = activeIndexPath;
            String newName = oldPath.getFileName().toString().replace(".active", ".raw");
            Files.move(oldPath, oldPath.resolveSibling(newName), StandardCopyOption.ATOMIC_MOVE);
        }

        this.activeIndexPath = activePath.resolveSibling(activePath.getFileName().toString() + ".index");
        this.index = new IndexSegment(activeIndexPath, indexSize);
        logger.debug("Index segment rotated to {}", activeIndexPath.getFileName());
    }

    private void ensureCapacity(long needed, CharSequence reqId) throws IOException
    {
        if (needed > segmentSize)
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
        if (!index.hasSpace())
        {
            rotateIndex();
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

    private void writeLowercasedHeaderName(CharSequence cs)
    {
        int len = cs.length();
        if (len > keyBuffer.length)
        {
            writePrefixedString(cs.toString().toLowerCase(Locale.ROOT));
            return;
        }
        for (int i = 0; i < len; i++)
        {
            char c = cs.charAt(i);
            keyBuffer[i] = (byte) ((c >= 'A' && c <= 'Z') ? (c | 0x20) : c);
        }
        buffer.putInt(len);
        buffer.put(keyBuffer, 0, len);
    }

    private void writePrefixedString(CharSequence s)
    {
        byte[] b = s.toString().getBytes(StandardCharsets.UTF_8);
        buffer.putInt(b.length);
        buffer.put(b);
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
}