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
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.core.ServerDirection;

public final class Journal
{
    private static final Logger logger = LoggerFactory.getLogger(Journal.class);
    private static final ThreadLocal<CharsetEncoder> ENCODER = ThreadLocal.withInitial(StandardCharsets.UTF_8::newEncoder);
    private final MappedByteBuffer buffer;
    private final Path path;
    private final byte[] keyBuffer = new byte[256]; // Header names are usually short

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

    public void writeBegin(ServerDirection dir, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers)
    {
        int startPos = buffer.position();
        try
        {
            buffer.put(Marker.BEGIN);
            buffer.putInt(dir.ordinal()); // 0 = REQUEST, 1 = RESPONSE (or use constants)

            writeString(reqId);
            writeBuffer(startLine);

            int countPos = buffer.position();
            buffer.putInt(0);

            int[] count = {0};
            headers.forEach((k, v) -> {
                writeLowercasedHeaderName(k);
                writeString(v);
                count[0]++;
            });

            buffer.putInt(countPos, count[0]);
        }
        catch (JournalOverflowException e)
        {
            rollback(startPos);
            throw e;
        }
        catch (Exception e)
        {
            rollback(startPos);
            throw new RuntimeException("Error writing BEGIN event", e);
        }
    }

    private void writeLowercasedHeaderName(CharSequence cs)
    {
        int len = cs.length();
        // 1. Ensure we don't overflow our reusable buffer
        if (len > keyBuffer.length)
        {
            // Fallback for ridiculously long (and likely invalid) headers
            writeString(cs.toString().toLowerCase(Locale.ROOT));
            return;
        }

        for (int i = 0; i < len; i++)
        {
            char c = cs.charAt(i);
            // Bitwise OR with 0x20 lowercases 'A'-'Z' while leaving
            // numbers and allowed symbols (!, #, $, %, etc.) untouched.
            if (c >= 'A' && c <= 'Z')
            {
                keyBuffer[i] = (byte) (c | 0x20);
            }
            else
            {
                keyBuffer[i] = (byte) c;
            }
        }

        writeBuffer(ByteBuffer.wrap(keyBuffer, 0, len));
    }

    /**
     * Now identifies the direction to support full-duplex auditing.
     */
    public void writeBody(ServerDirection dir, CharSequence reqId, ByteBuffer data)
    {
        int startPos = buffer.position();
        try
        {
            buffer.put(Marker.BODY);
            buffer.putInt(dir.ordinal()); // CRITICAL: Identify if this is REQ or RES body
            writeString(reqId);
            writeBuffer(data);
        }
        catch (JournalOverflowException e)
        {
            rollback(startPos);
            throw e;
        }
        catch (Exception e)
        {
            rollback(startPos);
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
            rollback(startPos);
            throw e;
        }
        catch (Exception e)
        {
            rollback(startPos);
            throw new RuntimeException("Error writing END event", e);
        }
    }

    private void rollback(int startPos)
    {
        buffer.put(startPos, (byte) 0);
        buffer.position(startPos);
    }

    private void writeString(final CharSequence s)
    {
        if (s == null)
        {
            if (buffer.remaining() < 4)
            {
                throw new JournalOverflowException("No space for null string");
            }
            buffer.putInt(-1);
            return;
        }

        if (buffer.remaining() < 4)
        {
            throw new JournalOverflowException("No space for string length");
        }
        final int lengthPos = buffer.position();
        buffer.putInt(0);

        final CharsetEncoder encoder = ENCODER.get();
        encoder.reset();

        // Standard wrap is fine for now; in a strict zero-alloc world we'd use a
        // ThreadLocal CharBuffer to avoid the wrapper object allocation.
        final CharBuffer charBuffer = CharBuffer.wrap(s);

        final CoderResult result = encoder.encode(charBuffer, buffer, true);
        if (result.isOverflow())
        {
            throw new JournalOverflowException("Buffer overflow writing string");
        }
        encoder.flush(buffer);

        final int length = buffer.position() - lengthPos - 4;
        buffer.putInt(lengthPos, length);
    }

    private void writeBuffer(ByteBuffer src)
    {
        if (src == null)
        {
            if (buffer.remaining() < 4) throw new JournalOverflowException("No space for null buffer");
            buffer.putInt(-1);
            return;
        }

        int len = src.remaining();
        if (buffer.remaining() < 4 + len) throw new JournalOverflowException("No space for buffer data");

        buffer.putInt(len);
        buffer.put(src.duplicate()); // duplicate() avoids changing caller's position
    }

    public boolean hasSpace(int bytesNeeded)
    {
        return buffer.remaining() >= bytesNeeded;
    }

    public void force()
    {
        buffer.force();
    }

    public Path getPath()
    {
        return path;
    }
}