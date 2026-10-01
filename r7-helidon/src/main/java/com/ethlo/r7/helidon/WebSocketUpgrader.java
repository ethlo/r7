package com.ethlo.r7.helidon;

import java.io.IOException;
import java.time.Duration;

import com.ethlo.r7.server.blocking.BlockingGateway;
import com.ethlo.r7.upstream.Tunnel;
import io.helidon.common.buffers.BufferData;
import io.helidon.common.buffers.DataReader;
import io.helidon.common.concurrency.limits.Limit;
import io.helidon.http.HttpPrologue;
import io.helidon.http.WritableHeaders;
import io.helidon.webserver.ConnectionContext;
import io.helidon.webserver.http1.spi.Http1Upgrader;
import io.helidon.webserver.spi.ServerConnection;

/**
 * Takes every {@code Upgrade: websocket} request off Helidon's HTTP/1.1 connection and runs it
 * through r7 over the raw connection, so that a 101 from the upstream can turn the connection
 * into a {@link Tunnel}. Helidon offers the raw connection only here, before routing: a routed
 * handler's response cannot be switched to another protocol. So the whole exchange runs here,
 * pipeline and journal included ({@link UpgradeExchange}), and a request the upstream or a filter
 * does not switch is answered and the connection closed.
 * <p>
 * Helidon routes an upgrade request with a body normally, and the relay then refuses a 101 for
 * it with 502; so does a request whose Upgrade value is not exactly {@code websocket}, which is
 * what Helidon matches on. Browsers send exactly that.
 */
final class WebSocketUpgrader implements Http1Upgrader
{
    private final BlockingGateway gateway;

    WebSocketUpgrader(final BlockingGateway gateway)
    {
        this.gateway = gateway;
    }

    @Override
    public String supportedProtocol()
    {
        return "websocket";
    }

    @Override
    public ServerConnection upgrade(final ConnectionContext ctx, final HttpPrologue prologue, final WritableHeaders<?> headers)
    {
        // Nothing is read or written here: the connection's handle() does it all, on the
        // connection's own thread, and Helidon closes the connection when it returns.
        return new Connection(ctx, prologue, headers);
    }

    private final class Connection implements ServerConnection
    {
        private final ConnectionContext ctx;
        private final HttpPrologue prologue;
        private final WritableHeaders<?> headers;
        private volatile UpgradeExchange exchange;
        private volatile Thread thread;
        private volatile boolean closing;

        Connection(final ConnectionContext ctx, final HttpPrologue prologue, final WritableHeaders<?> headers)
        {
            this.ctx = ctx;
            this.prologue = prologue;
            this.headers = headers;
        }

        @Override
        public void handle(final Limit limit)
        {
            this.thread = Thread.currentThread();
            final UpgradeExchange current = new UpgradeExchange(gateway.pipeline(), this.ctx, this.prologue, this.headers);
            this.exchange = current;
            try
            {
                gateway.handle(current);
            }
            catch (final BlockingGateway.ResponseAbortedException e)
            {
                // Returning closes the connection, which is all the abort asks for.
            }
            finally
            {
                if (current.switchedProtocols())
                {
                    if (this.closing)
                    {
                        current.abandonTunnel();
                    }
                    else
                    {
                        current.runTunnel(new Client(this.ctx, Thread.currentThread()));
                    }
                }
            }
        }

        @Override
        public Duration idleTime()
        {
            final UpgradeExchange current = this.exchange;
            final Tunnel tunnel = current != null && current.switchedProtocols() ? current.openTunnel() : null;
            return tunnel == null ? Duration.ZERO : Duration.ofNanos(tunnel.idleNanos());
        }

        @Override
        public void close(final boolean interrupt)
        {
            // Idle timeout or server shutdown.
            this.closing = true;
            final UpgradeExchange current = this.exchange;
            if (current != null && current.switchedProtocols())
            {
                current.openTunnel().close();
            }
            else if (interrupt && this.thread != null)
            {
                this.thread.interrupt();
            }
        }
    }

    /**
     * The client connection as the tunnel sees it, over Helidon's reader and writer.
     */
    private static final class Client implements Tunnel.Client
    {
        private final ConnectionContext ctx;
        private final Thread reader;

        Client(final ConnectionContext ctx, final Thread reader)
        {
            this.ctx = ctx;
            this.reader = reader;
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException
        {
            try
            {
                final DataReader in = this.ctx.dataReader();
                in.ensureAvailable();
                final int n = Math.min(length, in.available());
                in.readBuffer(n).read(buffer, offset, n);
                return n;
            }
            catch (final DataReader.InsufficientDataAvailableException e)
            {
                return -1;
            }
            catch (final RuntimeException e)
            {
                // Helidon reports a failed or interrupted socket read unchecked.
                throw new IOException(e.getMessage(), e);
            }
        }

        @Override
        public void write(final byte[] buffer, final int offset, final int length) throws IOException
        {
            try
            {
                // Written before it returns, so the buffer may be reused.
                this.ctx.dataWriter().writeNow(BufferData.create(buffer, offset, length));
            }
            catch (final RuntimeException e)
            {
                throw new IOException(e.getMessage(), e);
            }
        }

        @Override
        public void close()
        {
            // There is no handle on the socket (ConnectionContext.serverSocket() throws), but it is
            // an interruptible channel: interrupting the thread blocked reading it closes it, which
            // ends a write blocked on it too. Helidon closes it properly once handle() returns.
            if (Thread.currentThread() != this.reader)
            {
                this.reader.interrupt();
            }
        }
    }
}
