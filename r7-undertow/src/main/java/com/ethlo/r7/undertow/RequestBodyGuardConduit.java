package com.ethlo.r7.undertow;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.xnio.IoUtils;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.conduits.AbstractStreamSourceConduit;
import org.xnio.conduits.ConduitReadableByteChannel;
import org.xnio.conduits.StreamSourceConduit;

import io.undertow.UndertowMessages;
import io.undertow.server.HttpServerExchange;

/**
 * Guards a proxied request body that the upstream will receive chunked: counts it against an
 * optional limit, and on any failure to read it - the limit, a client that disconnects
 * mid-body, a malformed chunk - aborts the upstream connection before the failure propagates,
 * so the upstream never sees a truncated body closed off as complete (see {@link UpstreamAbort}).
 * <p>
 * Counting here rather than via {@code setMaxEntitySize} is deliberate: on HTTP/1.1 Undertow's
 * chunked decoder takes its limit when the request is parsed, before any filter has run.
 */
public final class RequestBodyGuardConduit extends AbstractStreamSourceConduit<StreamSourceConduit>
{
    private final HttpServerExchange exchange;
    private final long maxBytes;
    private long bytesRead;

    /**
     * @param maxBytes the largest body to accept, or {@link Long#MAX_VALUE} for no limit of its own
     */
    public RequestBodyGuardConduit(final StreamSourceConduit next, final HttpServerExchange exchange, final long maxBytes)
    {
        super(next);
        this.exchange = exchange;
        this.maxBytes = maxBytes;
    }

    @Override
    public int read(final ByteBuffer dst) throws IOException
    {
        final int read;
        try
        {
            read = next.read(dst);
        }
        catch (final IOException e)
        {
            throw abort(e);
        }
        if (read > 0)
        {
            count(read);
        }
        return read;
    }

    @Override
    public long read(final ByteBuffer[] dsts, final int offset, final int length) throws IOException
    {
        final long read;
        try
        {
            read = next.read(dsts, offset, length);
        }
        catch (final IOException e)
        {
            throw abort(e);
        }
        if (read > 0)
        {
            count(read);
        }
        return read;
    }

    /**
     * Routed through {@link #read(ByteBuffer)}: the inherited implementation hands the transfer
     * straight to the next conduit, and those bytes would be neither counted nor guarded.
     */
    @Override
    public long transferTo(final long position, final long count, final FileChannel target) throws IOException
    {
        return target.transferFrom(new ConduitReadableByteChannel(this), position, count);
    }

    @Override
    public long transferTo(final long count, final ByteBuffer throughBuffer, final StreamSinkChannel target) throws IOException
    {
        return IoUtils.transfer(new ConduitReadableByteChannel(this), count, throughBuffer, target);
    }

    private void count(final long read) throws IOException
    {
        this.bytesRead += read;
        if (this.bytesRead > this.maxBytes)
        {
            throw abort(UndertowMessages.MESSAGES.requestEntityWasTooLarge(this.maxBytes));
        }
    }

    private IOException abort(final IOException cause)
    {
        // The body is only partly consumed: neither connection can carry another request.
        this.exchange.setPersistent(false);
        UpstreamAbort.abort(this.exchange);
        return cause;
    }
}
