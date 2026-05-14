package com.ethlo.r7.undertow;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

import org.xnio.conduits.AbstractStreamSourceConduit;
import org.xnio.conduits.StreamSourceConduit;

public final class TeeingStreamSourceConduit extends AbstractStreamSourceConduit<StreamSourceConduit>
{
    private final Consumer<ByteBuffer> listener;

    public TeeingStreamSourceConduit(StreamSourceConduit next, Consumer<ByteBuffer> listener)
    {
        super(next);
        this.listener = listener;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException
    {
        final int posBefore = dst.position();
        final int read = next.read(dst);
        if (read > 0)
        {
            tee(dst, posBefore, read);
        }
        return read;
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException
    {
        final int[] positions = new int[length];
        for (int i = 0; i < length; i++)
        {
            positions[i] = dsts[offset + i].position();
        }

        final long totalRead = next.read(dsts, offset, length);
        if (totalRead > 0)
        {
            long remaining = totalRead;
            for (int i = 0; i < length && remaining > 0; i++)
            {
                final ByteBuffer buf = dsts[offset + i];
                final int readInBuf = (int) Math.min(buf.position() - positions[i], remaining);
                if (readInBuf > 0)
                {
                    tee(buf, positions[i], readInBuf);
                    remaining -= readInBuf;
                }
            }
        }
        return totalRead;
    }

    private void tee(ByteBuffer buf, int position, int length)
    {
        final ByteBuffer slice = buf.duplicate();
        slice.position(position);
        slice.limit(position + length);
        listener.accept(slice);
    }
}