package com.ethlo.r7.servlet;

import java.io.IOException;

import com.ethlo.r7.server.blocking.BlockingServerExchange;
import com.ethlo.r7.upstream.Tunnel;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.WebConnection;

/**
 * The servlet side of a WebSocket the upstream accepted: the container upgrades the connection
 * once {@code service()} has returned and hands it over in {@link #init}, where the tunnel starts
 * on a virtual thread of its own - {@code init} runs on a container thread, which must not be
 * held for the life of the WebSocket.
 * <p>
 * Public with a public constructor only because the container instantiates it; it is not for
 * use outside r7.
 */
public final class TunnelHandler implements HttpUpgradeHandler
{
    private volatile BlockingServerExchange exchange;

    public TunnelHandler()
    {
    }

    void exchange(final BlockingServerExchange exchange)
    {
        this.exchange = exchange;
    }

    @Override
    public void init(final WebConnection connection)
    {
        final BlockingServerExchange current = this.exchange;
        try
        {
            final Client client = new Client(connection.getInputStream(), connection.getOutputStream());
            Thread.ofVirtual().name("r7-tunnel").start(() -> current.runTunnel(client));
        }
        catch (final IOException | RuntimeException e)
        {
            current.abandonTunnel();
        }
    }

    @Override
    public void destroy()
    {
        // The connection is gone, or the container is stopping. A tunnel that already ended is
        // left as it is; one that never started releases its upstream connection.
        final BlockingServerExchange current = this.exchange;
        if (current != null)
        {
            current.abandonTunnel();
        }
    }

    /**
     * The upgraded connection's streams; with no read or write listener set, the container's
     * upgraded streams block.
     */
    private static final class Client implements Tunnel.Client
    {
        private final ServletInputStream in;
        private final ServletOutputStream out;
        private volatile Thread reader;

        Client(final ServletInputStream in, final ServletOutputStream out)
        {
            this.in = in;
            this.out = out;
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException
        {
            this.reader = Thread.currentThread();
            return this.in.read(buffer, offset, length);
        }

        @Override
        public void write(final byte[] buffer, final int offset, final int length) throws IOException
        {
            try
            {
                this.out.write(buffer, offset, length);
                this.out.flush();
            }
            catch (final IllegalStateException e)
            {
                // The stream was switched to non-blocking by close(), from the other direction.
                throw new IOException(e.getMessage(), e);
            }
        }

        /**
         * WebConnection.close() does not close the socket on Tomcat: it marks the two streams
         * closed, and the container closes the socket only when it next processes an event for
         * it - which a blocking stream never asks for. A client waiting for the server to close
         * after the closing handshake would wait for the container's timeout. A write listener
         * makes the container dispatch a write event as soon as the socket is writable; closing
         * the output from it lets the container find both streams closed and close the socket.
         * A container that closes the socket on WebConnection.close() gets the same from this.
         */
        @Override
        public void close()
        {
            try
            {
                this.in.close();
            }
            catch (final IOException ignored)
            {
                // Closing is best effort.
            }
            try
            {
                this.out.setWriteListener(new WriteListener()
                {
                    @Override
                    public void onWritePossible() throws IOException
                    {
                        out.close();
                    }

                    @Override
                    public void onError(final Throwable t)
                    {
                        // The connection failed; the container closes it.
                    }
                });
            }
            catch (final IllegalStateException | IllegalArgumentException e)
            {
                // Already closed, or a listener already set: nothing more to ask of the container.
            }
            // A read blocked in the other direction's thread is not ended by any of the above.
            final Thread blocked = this.reader;
            if (blocked != null && blocked != Thread.currentThread())
            {
                blocked.interrupt();
            }
        }
    }
}
