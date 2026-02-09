package com.ethlo.venturi.undertow;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.xnio.conduits.AbstractStreamSourceConduit;
import org.xnio.conduits.StreamSourceConduit;

/**
 * A conduit that "tees" data from a source (Request Body) into an OutputStream
 * while it is being read by the engine.
 */
public final class TeeingStreamSourceConduit extends AbstractStreamSourceConduit<StreamSourceConduit>
{
    private final OutputStream out;

    public TeeingStreamSourceConduit(final StreamSourceConduit next, final OutputStream out)
    {
        super(next);
        this.out = out;
    }

    @Override
    public int read(final ByteBuffer dst) throws IOException
    {
        // Record starting position to identify the new data read
        final int pos = dst.position();
        final int read = next.read(dst);

        if (read > 0)
        {
            // Synchronously copy the read bytes to the audit stream
            final int limit = dst.limit();
            dst.limit(dst.position());
            dst.position(pos);
            try
            {
                while (dst.hasRemaining())
                {
                    out.write(dst.get());
                }
            } finally
            {
                // Restore the original buffer state for the engine
                dst.limit(limit);
            }
        }
        return read;
    }

    @Override
    public long read(final ByteBuffer[] dsts, final int offs, final int len) throws IOException
    {
        long totalRead = 0;
        for (int i = offs; i < offs + len; i++)
        {
            final int read = read(dsts[i]);
            if (read > 0)
            {
                totalRead += read;
            }
            else if (read == -1 && totalRead == 0)
            {
                return -1;
            }
            else
            {
                break;
            }
        }
        return totalRead;
    }

    @Override
    public long transferTo(final long position, final long count, final FileChannel target) throws IOException
    {
        // Fallback to standard read to ensure auditing is not bypassed by zero-copy OS transfers
        return next.transferTo(position, count, target);
    }
}