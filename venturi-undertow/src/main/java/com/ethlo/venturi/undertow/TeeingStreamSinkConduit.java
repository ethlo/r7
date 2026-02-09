package com.ethlo.venturi.undertow;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.xnio.Pool;
import org.xnio.Pooled;
import org.xnio.conduits.AbstractStreamSinkConduit;
import org.xnio.conduits.StreamSinkConduit;

public class TeeingStreamSinkConduit extends AbstractStreamSinkConduit<StreamSinkConduit> {
    private final OutputStream tee;
    private final Pool<ByteBuffer> pool;

    public TeeingStreamSinkConduit(StreamSinkConduit next, OutputStream tee, Pool<ByteBuffer> pool) {
        super(next);
        this.tee = tee;
        this.pool = pool;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        if (!src.hasRemaining()) return 0;
        ByteBuffer dup = src.duplicate();
        int written = next.write(src);
        if (written > 0) {
            dup.limit(dup.position() + written);
            writeToTee(dup);
        }
        return written;
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        ByteBuffer[] dups = new ByteBuffer[length];
        for (int i = 0; i < length; i++) {
            dups[i] = srcs[offset + i].duplicate();
        }

        long totalWritten = next.write(srcs, offset, length);
        if (totalWritten > 0) {
            long remaining = totalWritten;
            for (ByteBuffer buf : dups) {
                if (remaining <= 0) break;
                int amount = (int) Math.min(buf.remaining(), remaining);
                buf.limit(buf.position() + amount);
                writeToTee(buf);
                remaining -= amount;
            }
        }
        return totalWritten;
    }

    @Override
    public long transferFrom(FileChannel src, long position, long count) throws IOException {
        long transferred = next.transferFrom(src, position, count);
        if (transferred > 0) {
            // Use the pool instead of allocation to maintain Nitro speed
            try (Pooled<ByteBuffer> pooled = pool.allocate()) {
                ByteBuffer buf = pooled.getResource();
                long remaining = transferred;
                long currentPos = position;

                while (remaining > 0) {
                    buf.clear();
                    if (remaining < buf.capacity()) {
                        buf.limit((int) remaining);
                    }
                    int read = src.read(buf, currentPos);
                    if (read <= 0) break;
                    buf.flip();
                    writeToTee(buf);
                    currentPos += read;
                    remaining -= read;
                }
            }
        }
        return transferred;
    }

    private void writeToTee(ByteBuffer buf) throws IOException {
        if (buf.hasArray()) {
            tee.write(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining());
        } else {
            // Nitro tip: Use a thread-local byte array to avoid this allocation
            byte[] b = new byte[buf.remaining()];
            buf.get(b);
            tee.write(b);
        }
    }

    @Override
    public void terminateWrites() throws IOException {
        try {
            tee.flush();
        } finally {
            next.terminateWrites();
        }
    }
}