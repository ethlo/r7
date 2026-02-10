package com.ethlo.venturi.undertow;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.function.Consumer;

import org.xnio.conduits.AbstractStreamSinkConduit;
import org.xnio.conduits.StreamSinkConduit;

public final class TeeingStreamSinkConduit extends AbstractStreamSinkConduit<StreamSinkConduit>
{
    private final Consumer<ByteBuffer> listener;

    public TeeingStreamSinkConduit(StreamSinkConduit next, Consumer<ByteBuffer> listener)
    {
        super(next);
        this.listener = listener;
    }

    @Override
    public int write(ByteBuffer src) throws IOException
    {
        final int posBefore = src.position();
        final int written = next.write(src);
        if (written > 0)
        {
            tee(src, posBefore, written);
        }
        return written;
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException
    {
        final int[] positions = new int[length];
        for (int i = 0; i < length; i++)
        {
            positions[i] = srcs[offset + i].position();
        }

        final long totalWritten = next.write(srcs, offset, length);
        if (totalWritten > 0)
        {
            long remaining = totalWritten;
            for (int i = 0; i < length && remaining > 0; i++)
            {
                final ByteBuffer buf = srcs[offset + i];
                final int writtenFromBuf = (int) Math.min(buf.position() - positions[i], remaining);
                if (writtenFromBuf > 0)
                {
                    tee(buf, positions[i], writtenFromBuf);
                    remaining -= writtenFromBuf;
                }
            }
        }
        return totalWritten;
    }

    @Override
    public long transferFrom(FileChannel src, long position, long count) throws IOException
    {
        // ESSENTIAL for static file proxying (sendfile path)
        final long transferred = next.transferFrom(src, position, count);
        if (transferred > 0)
        {
            // Since this is zero-copy in the kernel, we must manually read the
            // bytes from the file to 'tee' them to our log.
            final ByteBuffer debugBuf = ByteBuffer.allocate((int) Math.min(transferred, 65536));
            long currentPos = position;
            long remaining = transferred;
            while (remaining > 0)
            {
                debugBuf.clear();
                int toRead = (int) Math.min(debugBuf.capacity(), remaining);
                debugBuf.limit(toRead);
                int read = src.read(debugBuf, currentPos);
                if (read <= 0)
                {
                    break;
                }
                debugBuf.flip();
                listener.accept(debugBuf);
                currentPos += read;
                remaining -= read;
            }
        }
        return transferred;
    }

    private void tee(ByteBuffer buf, int position, int length)
    {
        final ByteBuffer slice = buf.duplicate();
        slice.position(position);
        slice.limit(position + length);
        listener.accept(slice);
    }
}