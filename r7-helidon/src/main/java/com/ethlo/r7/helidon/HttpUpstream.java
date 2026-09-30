package com.ethlo.r7.helidon;

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
        final LineReader reader;
        final OutputStream out;
        boolean reused;

        Connection(final Socket socket) throws IOException
        {
            this.socket = socket;
            this.reader = new LineReader(socket.getInputStream());
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

    /**
     * A buffered reader over the upstream socket for a response head and body: lines are scanned
     * in the buffer, and body reads are served from it before going to the socket. Unsynchronised,
     * unlike BufferedInputStream, whose per-call lock showed in the profile when the head was read
     * a byte at a time; a connection is only ever used by one request at a time.
     */
    static final class LineReader
    {
        private final InputStream in;
        private final byte[] buffer = new byte[8192];
        private int pos;
        private int limit;

        LineReader(final InputStream in)
        {
            this.in = in;
        }

        private boolean fill() throws IOException
        {
            final int n = in.read(buffer, 0, buffer.length);
            if (n <= 0)
            {
                return false;
            }
            pos = 0;
            limit = n;
            return true;
        }

        /**
         * One CRLF- or LF-terminated line as ISO-8859-1; {@code null} at end of stream.
         */
        String readLine() throws IOException
        {
            StringBuilder spill = null;
            while (true)
            {
                if (pos == limit && !fill())
                {
                    return spill == null || spill.isEmpty() ? null : spill.toString();
                }
                for (int i = pos; i < limit; i++)
                {
                    if (buffer[i] == '\n')
                    {
                        int end = i;
                        final String line;
                        if (spill == null)
                        {
                            if (end > pos && buffer[end - 1] == '\r')
                            {
                                end--;
                            }
                            line = new String(buffer, 0, pos, end - pos);
                        }
                        else
                        {
                            spill.append(new String(buffer, 0, pos, end - pos));
                            final int length = spill.length();
                            if (length > 0 && spill.charAt(length - 1) == '\r')
                            {
                                spill.setLength(length - 1);
                            }
                            line = spill.toString();
                        }
                        pos = i + 1;
                        return line;
                    }
                }
                if (spill == null)
                {
                    spill = new StringBuilder(128);
                }
                spill.append(new String(buffer, 0, pos, limit - pos));
                pos = limit;
            }
        }

        int read(final byte[] target, final int offset, final int length) throws IOException
        {
            if (pos < limit)
            {
                final int n = Math.min(length, limit - pos);
                System.arraycopy(buffer, pos, target, offset, n);
                pos += n;
                return n;
            }
            return in.read(target, offset, length);
        }
    }
}
