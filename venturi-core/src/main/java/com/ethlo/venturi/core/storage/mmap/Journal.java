package com.ethlo.venturi.core.storage.mmap;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.api.GatewayHeaders;

public final class Journal
{
    private static final Logger logger = LoggerFactory.getLogger(Journal.class);
    private static final ThreadLocal<CharsetEncoder> ENCODER = ThreadLocal.withInitial(() -> StandardCharsets.UTF_8.newEncoder());
    private final MappedByteBuffer buffer;
    private final Path path;

    public Journal(Path path, long size) throws IOException
    {
        logger.debug("Creating Journal for {} - {}", path, Thread.currentThread().getName());
        this.path = path;
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw"))
        {
            raf.setLength(size);
            this.buffer = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, size);
            this.buffer.put(Marker.VERSION);
        }
    }

    public void writeBegin(int dir, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers)
    {
        int startPos = buffer.position();
        try
        {
            buffer.put(Marker.BEGIN);
            buffer.putInt(dir);

            writeString(reqId);
            writeBuffer(startLine);

            // 1. Record the current position and put a dummy 4-byte int
            int countPos = buffer.position();
            buffer.putInt(0);

            // 2. Write headers
            int[] count = {0};
            headers.forEach((k, v) -> {
                writeString(k);
                writeString(v);
                count[0]++;
            });

            // 3. Backfill the count at countPos
            // IMPORTANT: putInt(index, val) does NOT change the current position
            buffer.putInt(countPos, count[0]);
        }
        catch (JournalOverflowException e)
        {
            // Rewind to start of this event
            buffer.put(startPos, (byte) 0);
            buffer.position(startPos);
            throw e;
        }
        catch (Exception e)
        {
            // For other exceptions, we should also try to rollback
            buffer.put(startPos, (byte) 0);
            buffer.position(startPos);
            throw new RuntimeException("Error writing BEGIN event", e);
        }
    }

    public void writeBody(CharSequence reqId, ByteBuffer data)
    {
        int startPos = buffer.position();
        try
        {
            buffer.put(Marker.BODY);
            writeString(reqId);
            writeBuffer(data);
        }
        catch (JournalOverflowException e)
        {
            buffer.put(startPos, (byte) 0);
            buffer.position(startPos);
            throw e;
        }
        catch (Exception e)
        {
            buffer.put(startPos, (byte) 0);
            buffer.position(startPos);
            throw new RuntimeException("Error writing BODY event", e);
        }
    }

    public void writeEnd(CharSequence reqId, int status, long bytesSent, long bytesReceived, long durationNanos)
    {
        int startPos = buffer.position();
        try
        {
            buffer.put(Marker.END);
            writeString(reqId);
            buffer.putLong(System.currentTimeMillis());
            buffer.putInt(status);
            buffer.putLong(bytesSent);
            buffer.putLong(bytesReceived);
            buffer.putLong(durationNanos);
        }
        catch (JournalOverflowException e)
        {
            buffer.put(startPos, (byte) 0);
            buffer.position(startPos);
            throw e;
        }
        catch (Exception e)
        {
            buffer.put(startPos, (byte) 0);
            buffer.position(startPos);
            throw new RuntimeException("Error writing END event", e);
        }
    }

    private void writeString(CharSequence s)
    {
        if (s == null)
        {
            if (buffer.remaining() < 4)
            {
                throw new JournalOverflowException("Not enough space for null string marker");
            }
            buffer.putInt(-1);
            return;
        }

        // Zero-allocation string writing
        // 1. Reserve 4 bytes for length
        if (buffer.remaining() < 4)
        {
            throw new JournalOverflowException("Not enough space for string length");
        }
        int lengthPos = buffer.position();
        buffer.putInt(0); // Placeholder

        // 2. Encode directly into buffer
        CharsetEncoder encoder = ENCODER.get();
        encoder.reset();

        // We need to wrap CharSequence in a CharBuffer
        CharBuffer charBuffer = CharBuffer.wrap(s);

        // Encode directly to the MappedByteBuffer
        CoderResult result = encoder.encode(charBuffer, buffer, true);
        if (result.isOverflow())
        {
            throw new JournalOverflowException("Buffer overflow while writing string");
        }
        encoder.flush(buffer);

        // 3. Calculate length and update placeholder
        int endPos = buffer.position();
        int length = endPos - lengthPos - 4; // Subtract the 4 bytes used for length itself
        buffer.putInt(lengthPos, length);
    }

    private void writeBuffer(ByteBuffer src)
    {
        if (src == null)
        {
            if (buffer.remaining() < 4)
            {
                throw new JournalOverflowException("Not enough space for null buffer marker");
            }
            buffer.putInt(-1);
            return;
        }

        int len = src.remaining();
        if (buffer.remaining() < 4 + len)
        {
            throw new JournalOverflowException("Not enough space for buffer data");
        }

        buffer.putInt(len);

        // Bulk put is efficient
        buffer.put(src.duplicate());
    }

    public boolean hasSpace(int bytesNeeded)
    {
        return buffer.remaining() >= bytesNeeded;
    }

    public Path getPath()
    {
        return path;
    }

    public void force()
    {
        buffer.force();
    }
}