package com.ethlo.r7.server.kit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An upstream that answers with exactly the bytes a test scripts, however broken: the other half
 * of {@link UpstreamConformanceKit}. Each accepted connection runs the script on a virtual thread
 * of its own, and the upstream counts connections and requests so a test can tell whether the
 * gateway reused a connection, opened a new one, or sent a request twice.
 */
public final class ScriptedUpstream implements AutoCloseable
{
    /**
     * What to do with one connection.
     */
    @FunctionalInterface
    public interface Script
    {
        void run(Connection connection) throws Exception;
    }

    /**
     * One request as received: the request line, the head, and the body (de-chunked).
     */
    public record Request(String requestLine, String head, byte[] body)
    {
        public String method()
        {
            return requestLine.substring(0, requestLine.indexOf(' '));
        }
    }

    private final ServerSocket server;
    private final Script script;
    private final AtomicInteger connections = new AtomicInteger();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public ScriptedUpstream(final Script script) throws IOException
    {
        this.script = script;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("scripted-upstream-" + this.server.getLocalPort()).start(this::acceptLoop);
    }

    public String url()
    {
        return "http://127.0.0.1:" + this.server.getLocalPort();
    }

    /**
     * Connections accepted so far.
     */
    public int connections()
    {
        return this.connections.get();
    }

    /**
     * Requests received so far, across connections.
     */
    public List<Request> requests()
    {
        return this.requests;
    }

    private void acceptLoop()
    {
        while (!this.closed)
        {
            try
            {
                final Socket socket = this.server.accept();
                this.open.add(socket);
                final int index = this.connections.incrementAndGet();
                Thread.ofVirtual().start(() ->
                {
                    try (socket)
                    {
                        this.script.run(new Connection(socket, index));
                    }
                    catch (final Exception ignored)
                    {
                        // A gateway closing its side mid-script is part of what is being tested.
                    }
                });
            }
            catch (final IOException e)
            {
                return;
            }
        }
    }

    @Override
    public void close() throws IOException
    {
        this.closed = true;
        this.server.close();
        for (final Socket socket : this.open)
        {
            socket.close();
        }
    }

    /**
     * One accepted connection, with the primitives a script needs.
     */
    public final class Connection
    {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final int index;

        Connection(final Socket socket, final int index) throws IOException
        {
            this.socket = socket;
            this.socket.setSoTimeout(10_000);
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
            this.index = index;
        }

        /**
         * 1 for the first connection this upstream accepted, 2 for the second, ...
         */
        public int index()
        {
            return this.index;
        }

        /**
         * The next request, recorded; null when the gateway closed the connection first.
         */
        public Request readRequest() throws IOException
        {
            final String requestLine = readLine();
            if (requestLine == null)
            {
                return null;
            }
            final StringBuilder head = new StringBuilder(requestLine).append("\r\n");
            long contentLength = 0;
            boolean chunked = false;
            String line;
            while ((line = readLine()) != null && !line.isEmpty())
            {
                head.append(line).append("\r\n");
                final String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-length:"))
                {
                    contentLength = Long.parseLong(line.substring(15).trim());
                }
                else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked"))
                {
                    chunked = true;
                }
            }
            final ByteArrayOutputStream body = new ByteArrayOutputStream();
            if (chunked)
            {
                long size;
                while ((size = Long.parseLong(readLine().trim(), 16)) > 0)
                {
                    body.write(this.in.readNBytes((int) size));
                    readLine();
                }
                readLine();
            }
            else
            {
                body.write(this.in.readNBytes((int) contentLength));
            }
            final Request request = new Request(requestLine, head.toString(), body.toByteArray());
            requests.add(request);
            return request;
        }

        /**
         * Reads just the request head, leaving any body unread; recorded like a whole request.
         */
        public Request readHeadOnly() throws IOException
        {
            final String requestLine = readLine();
            if (requestLine == null)
            {
                return null;
            }
            final StringBuilder head = new StringBuilder(requestLine).append("\r\n");
            String line;
            while ((line = readLine()) != null && !line.isEmpty())
            {
                head.append(line).append("\r\n");
            }
            final Request request = new Request(requestLine, head.toString(), new byte[0]);
            requests.add(request);
            return request;
        }

        public void write(final String bytes) throws IOException
        {
            this.out.write(bytes.getBytes(StandardCharsets.ISO_8859_1));
            this.out.flush();
        }

        /**
         * Closes the way nginx does after an error response: stop sending, keep reading (and
         * discarding) for a while so the client's unread body does not provoke a reset that would
         * destroy the response in its receive buffer, then close.
         */
        public void lingeringClose() throws IOException
        {
            this.socket.shutdownOutput();
            this.socket.setSoTimeout(1_000);
            final byte[] sink = new byte[8192];
            try
            {
                while (this.in.read(sink) != -1)
                {
                    // Drain.
                }
            }
            catch (final IOException ignored)
            {
                // Timed out or reset: either way, done lingering.
            }
            this.socket.close();
        }

        /**
         * Waits for the gateway to close its side; the script ends when this returns.
         */
        public void awaitClose()
        {
            try
            {
                this.socket.setSoTimeout(0);
                while (this.in.read() != -1)
                {
                    // Discard.
                }
            }
            catch (final IOException ignored)
            {
                // Closed.
            }
        }

        private String readLine() throws IOException
        {
            final StringBuilder sb = new StringBuilder();
            int b;
            try
            {
                while ((b = this.in.read()) != -1)
                {
                    if (b == '\n')
                    {
                        final int length = sb.length();
                        if (length > 0 && sb.charAt(length - 1) == '\r')
                        {
                            sb.setLength(length - 1);
                        }
                        return sb.toString();
                    }
                    sb.append((char) b);
                }
            }
            catch (final SocketException e)
            {
                return null;
            }
            return sb.isEmpty() ? null : sb.toString();
        }
    }
}
