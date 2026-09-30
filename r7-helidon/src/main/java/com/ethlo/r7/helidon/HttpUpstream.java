package com.ethlo.r7.helidon;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.server.UpstreamHandle;

/**
 * A route's upstream for r7-helidon: the targets the health monitor reports up, picked round
 * robin, and a pool of idle keep-alive connections per target. Plain blocking sockets: every
 * request already runs on its own virtual thread, so a blocked read parks that thread and
 * nothing else.
 * <p>
 * Spike scope (design/server-spi.md, step 6): HTTP/1.1 over plain TCP only - no upstream TLS, no
 * retries beyond one reconnect for a pooled connection the upstream had already closed, no
 * WebSocket tunnelling, no 100-continue. Nothing here names a Helidon type, so a servlet host
 * could use it as it is.
 */
final class HttpUpstream implements UpstreamHandle
{
    private static final Logger logger = LoggerFactory.getLogger(HttpUpstream.class);
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int MAX_IDLE_PER_TARGET = 64;

    private final List<Target> up = new CopyOnWriteArrayList<>();
    private final AtomicInteger next = new AtomicInteger();
    private final int readTimeoutMillis;

    HttpUpstream(final int readTimeoutMillis)
    {
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override
    public void onTargetUp(final URI target)
    {
        if ("https".equalsIgnoreCase(target.getScheme()))
        {
            throw new IllegalArgumentException("r7-helidon (experimental) does not support https upstreams yet: " + target);
        }
        for (final Target existing : up)
        {
            if (existing.uri.equals(target))
            {
                return;
            }
        }
        logger.info("Target {} is reported as available", target);
        up.add(new Target(target));
    }

    @Override
    public void onTargetDown(final URI target)
    {
        logger.info("Target {} is reported as unavailable", target);
        up.removeIf(t ->
        {
            if (t.uri.equals(target))
            {
                t.closeIdle();
                return true;
            }
            return false;
        });
    }

    /**
     * The next target, round robin; {@code null} if none is up.
     */
    Target pick()
    {
        final List<Target> targets = this.up;
        final int n = targets.size();
        if (n == 0)
        {
            return null;
        }
        try
        {
            return targets.get(Math.floorMod(next.getAndIncrement(), n));
        }
        catch (final IndexOutOfBoundsException e)
        {
            // A target went down between size() and get(); try once more with the new list.
            return targets.isEmpty() ? null : targets.getFirst();
        }
    }

    final class Target
    {
        final URI uri;
        final String host;
        final int port;
        /**
         * The target's path, prepended to every forwarded request target; empty for "/".
         */
        final String basePath;
        final String hostHeader;
        private final ConcurrentLinkedDeque<Connection> idle = new ConcurrentLinkedDeque<>();

        private Target(final URI uri)
        {
            this.uri = uri;
            this.host = uri.getHost();
            this.port = uri.getPort() != -1 ? uri.getPort() : 80;
            final String path = uri.getRawPath();
            this.basePath = path == null || path.equals("/") ? "" : path;
            this.hostHeader = uri.getPort() != -1 ? host + ":" + port : host;
        }

        /**
         * An idle pooled connection, or a new one; {@link Connection#reused} tells which.
         */
        Connection acquire() throws IOException
        {
            Connection c;
            while ((c = idle.pollFirst()) != null)
            {
                if (!c.socket.isClosed())
                {
                    c.reused = true;
                    return c;
                }
            }
            final Socket socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(readTimeoutMillis);
            return new Connection(socket);
        }

        void release(final Connection c)
        {
            if (idle.size() < MAX_IDLE_PER_TARGET && up.contains(this))
            {
                idle.addFirst(c);
            }
            else
            {
                c.close();
            }
        }

        private void closeIdle()
        {
            Connection c;
            while ((c = idle.pollFirst()) != null)
            {
                c.close();
            }
        }

        @Override
        public String toString()
        {
            return uri.toString();
        }
    }

    static final class Connection
    {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        boolean reused;

        Connection(final Socket socket) throws IOException
        {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream(), 8192);
            this.out = new BufferedOutputStream(socket.getOutputStream(), 8192);
        }

        void close()
        {
            try
            {
                socket.close();
            }
            catch (final IOException ignored)
            {
                // Closing is best effort.
            }
        }
    }
}
