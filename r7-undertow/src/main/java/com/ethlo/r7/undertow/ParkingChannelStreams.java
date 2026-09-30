package com.ethlo.r7.undertow;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.xnio.ChannelListener;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.channels.StreamSourceChannel;

import io.undertow.server.HttpServerExchange;
import io.undertow.server.ServerConnection;

/**
 * Blocking streams over an exchange's non-blocking request and response channels, for a virtual
 * thread: a read or write that would block parks the thread until the channel's listener, on
 * the I/O thread, says it can go on.
 * <p>
 * Not {@code exchange.startBlocking()} and Undertow's own blocking streams. Those wait through
 * XNIO's {@code awaitReadable}/{@code awaitWritable}, which call {@code Selector.select()} on a
 * selector held in a {@code ThreadLocal}: on a virtual thread that pins the carrier inside a
 * native call, and opens a selector - an epoll descriptor and a wakeup descriptor - for every
 * virtual thread that ever waits, closed only when finalization gets to it
 * (design/upstream.md). Both happen only when a peer is slow, which is why a benchmark never
 * shows them.
 * <p>
 * The channels' conduits are untouched, so journal tees, the traffic metrics and any
 * request-body guard still see every byte.
 */
final class ParkingChannelStreams
{
    /**
     * How often a parked thread wakes to check that its channel is still open. A listener fires
     * on close too, so this is a backstop, not the way a close is normally noticed.
     */
    private static final long RECHECK_NANOS = TimeUnit.SECONDS.toNanos(1);

    private ParkingChannelStreams()
    {
    }

    /**
     * Wakes the thread parked on a channel. One per wait, set as the channel's listener just
     * before it resumes, so a notification can never reach a thread that is not waiting for it.
     */
    private static final class Waiter implements ChannelListener<Channel>
    {
        private final Thread thread = Thread.currentThread();
        private final boolean reads;
        private volatile boolean ready;

        Waiter(final boolean reads)
        {
            this.reads = reads;
        }

        @Override
        public void handleEvent(final Channel channel)
        {
            // Stop the I/O thread reporting readiness again until the parked thread asks.
            if (this.reads && channel instanceof StreamSourceChannel source)
            {
                source.suspendReads();
            }
            else if (!this.reads && channel instanceof StreamSinkChannel sink)
            {
                sink.suspendWrites();
            }
            this.ready = true;
            LockSupport.unpark(this.thread);
        }

        /**
         * Parks until the listener fires or the client connection is gone. The connection, not
         * just the channel: the exchange's channel wrappers can stay "open" after the socket
         * under them closed, and a relay parked on one would hold its upstream connection for
         * good.
         */
        void await(final Channel channel, final ServerConnection connection)
        {
            while (!this.ready && channel.isOpen() && connection.isOpen())
            {
                LockSupport.parkNanos(this, RECHECK_NANOS);
            }
        }
    }

    static InputStream requestBody(final HttpServerExchange exchange)
    {
        final StreamSourceChannel channel = exchange.getRequestChannel();
        if (channel == null)
        {
            return InputStream.nullInputStream();
        }
        return new InputStream()
        {
            @Override
            public int read() throws IOException
            {
                final byte[] one = new byte[1];
                return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(final byte[] b, final int off, final int len) throws IOException
            {
                if (len == 0)
                {
                    return 0;
                }
                final ByteBuffer buffer = ByteBuffer.wrap(b, off, len);
                while (true)
                {
                    final int n = channel.read(buffer);
                    if (n != 0)
                    {
                        return n;
                    }
                    final Waiter waiter = new Waiter(true);
                    channel.getReadSetter().set(waiter);
                    channel.getCloseSetter().set(waiter);
                    channel.resumeReads();
                    waiter.await(channel, exchange.getConnection());
                    if (!channel.isOpen() || !exchange.getConnection().isOpen())
                    {
                        throw new IOException("The client connection closed while its request body was being read");
                    }
                }
            }
        };
    }

    /**
     * The response body stream. Closing it ends the exchange; {@link UndertowGatewayExchange}
     * aborts the connection instead when the body must not look complete.
     */
    static OutputStream responseBody(final HttpServerExchange exchange)
    {
        final StreamSinkChannel channel = exchange.getResponseChannel();
        return new OutputStream()
        {
            private boolean closed;

            @Override
            public void write(final int b) throws IOException
            {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(final byte[] b, final int off, final int len) throws IOException
            {
                final ByteBuffer buffer = ByteBuffer.wrap(b, off, len);
                while (buffer.hasRemaining())
                {
                    if (channel.write(buffer) == 0)
                    {
                        awaitWritable(channel, exchange.getConnection());
                    }
                }
            }

            @Override
            public void flush() throws IOException
            {
                while (!channel.flush())
                {
                    awaitWritable(channel, exchange.getConnection());
                }
            }

            @Override
            public void close() throws IOException
            {
                if (this.closed)
                {
                    return;
                }
                this.closed = true;
                channel.shutdownWrites();
                flush();
                exchange.endExchange();
            }
        };
    }

    private static void awaitWritable(final StreamSinkChannel channel, final ServerConnection connection) throws IOException
    {
        final Waiter waiter = new Waiter(false);
        channel.getWriteSetter().set(waiter);
        channel.getCloseSetter().set(waiter);
        channel.resumeWrites();
        waiter.await(channel, connection);
        if (!channel.isOpen() || !connection.isOpen())
        {
            throw new IOException("The client connection closed while the response was being written");
        }
    }
}
