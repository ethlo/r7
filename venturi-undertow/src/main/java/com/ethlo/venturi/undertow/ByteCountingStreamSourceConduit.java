package com.ethlo.venturi.undertow;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicLong;

import org.xnio.channels.StreamSinkChannel;
import org.xnio.conduits.AbstractStreamSourceConduit;
import org.xnio.conduits.StreamSourceConduit;

public final class ByteCountingStreamSourceConduit extends AbstractStreamSourceConduit<StreamSourceConduit>
{
    private final AtomicLong byteCount;

    public ByteCountingStreamSourceConduit(StreamSourceConduit next, AtomicLong byteCount)
    {
        super(next);
        this.byteCount = byteCount;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException
    {
        int res = next.read(dst);
        if (res > 0)
        {
            byteCount.addAndGet(res);
        }
        return res;
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException
    {
        long res = next.read(dsts, offset, length);
        if (res > 0)
        {
            byteCount.addAndGet(res);
        }
        return res;
    }

    @Override
    public long transferTo(long position, long count, FileChannel target) throws IOException
    {
        long res = next.transferTo(position, count, target);
        if (res > 0)
        {
            byteCount.addAndGet(res);
        }
        return res;
    }

    @Override
    public long transferTo(long count, ByteBuffer throughBuffer, StreamSinkChannel target) throws IOException
    {
        long res = next.transferTo(count, throughBuffer, target);
        if (res > 0)
        {
            byteCount.addAndGet(res);
        }
        return res;
    }
}