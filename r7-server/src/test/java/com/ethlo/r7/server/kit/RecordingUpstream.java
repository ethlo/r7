package com.ethlo.r7.server.kit;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * An upstream that records exactly what the gateway sent it: the request line, every header
 * field-line as written (name spelling, order and repeats), and the body as decoded from the
 * framing the gateway chose. It parses HTTP/1.1 itself, over a plain socket, because an HTTP
 * server library would normalise precisely what the security kit needs to see.
 * <p>
 * Answers every request {@code 200 OK} with a two-byte body and keeps the connection open, as a
 * pooled upstream connection expects.
 */
public final class RecordingUpstream implements AutoCloseable
{
    /**
     * One request as it arrived.
     */
    public record Received(String requestLine, List<String[]> headers, byte[] body)
    {
        /**
         * The values of every field-line named {@code name}, ignoring case, in order.
         */
        public List<String> values(final String name)
        {
            final List<String> out = new ArrayList<>();
            for (final String[] header : headers)
            {
                if (header[0].equalsIgnoreCase(name))
                {
                    out.add(header[1]);
                }
            }
            return out;
        }

        public boolean has(final String name)
        {
            return !values(name).isEmpty();
        }

        public String bodyText()
        {
            return new String(body, StandardCharsets.ISO_8859_1);
        }

        @Override
        public String toString()
        {
            final StringBuilder out = new StringBuilder(requestLine);
            for (final String[] header : headers)
            {
                out.append("\n  ").append(header[0]).append(": ").append(header[1]);
            }
            return out.toString();
        }
    }

    private static final byte[] RESPONSE = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Type: text/plain\r\n\r\nOK".getBytes(StandardCharsets.US_ASCII);

    private final ServerSocket server;
    private final BlockingQueue<Received> received = new LinkedBlockingQueue<>();
    private final Thread acceptor;

    public RecordingUpstream() throws IOException
    {
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.acceptor = Thread.ofVirtual().start(this::acceptLoop);
    }

    public int port()
    {
        return this.server.getLocalPort();
    }

    public String url()
    {
        return "http://127.0.0.1:" + port();
    }

    /**
     * The next request the upstream received, waiting up to {@code timeout}; {@code null} if none.
     */
    public Received next(final Duration timeout) throws InterruptedException
    {
        return this.received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void clear()
    {
        this.received.clear();
    }

    @Override
    public void close() throws IOException
    {
        this.server.close();
        this.acceptor.interrupt();
    }

    private void acceptLoop()
    {
        while (!this.server.isClosed())
        {
            try
            {
                final Socket socket = this.server.accept();
                Thread.ofVirtual().start(() -> serve(socket));
            }
            catch (final IOException e)
            {
                return;
            }
        }
    }

    private void serve(final Socket socket)
    {
        try (socket)
        {
            final InputStream in = new BufferedInputStream(socket.getInputStream());
            final OutputStream out = socket.getOutputStream();
            while (true)
            {
                final String requestLine = readLine(in);
                if (requestLine == null)
                {
                    return;
                }
                if (requestLine.isEmpty())
                {
                    continue;
                }
                final List<String[]> headers = new ArrayList<>();
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty())
                {
                    final int colon = line.indexOf(':');
                    headers.add(new String[]{line.substring(0, colon), line.substring(colon + 1).strip()});
                }
                final Received request = new Received(requestLine, headers, readBody(in, headers));
                this.received.add(request);
                out.write(RESPONSE);
                out.flush();
            }
        }
        catch (final IOException e)
        {
            // The gateway closed or reset the connection; whatever arrived was recorded.
        }
    }

    private static byte[] readBody(final InputStream in, final List<String[]> headers) throws IOException
    {
        String contentLength = null;
        boolean chunked = false;
        for (final String[] header : headers)
        {
            if (header[0].equalsIgnoreCase("Transfer-Encoding") && header[1].toLowerCase().contains("chunked"))
            {
                chunked = true;
            }
            else if (header[0].equalsIgnoreCase("Content-Length"))
            {
                contentLength = header[1];
            }
        }
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (chunked)
        {
            while (true)
            {
                final String sizeLine = readLine(in);
                if (sizeLine == null)
                {
                    throw new IOException("Connection closed inside a chunked body");
                }
                final int semicolon = sizeLine.indexOf(';');
                final int size = Integer.parseInt((semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon)).strip(), 16);
                if (size == 0)
                {
                    // Trailers, up to the empty line.
                    String trailer;
                    while ((trailer = readLine(in)) != null && !trailer.isEmpty())
                    {
                        // ignored
                    }
                    return body.toByteArray();
                }
                body.write(in.readNBytes(size));
                readLine(in);
            }
        }
        if (contentLength != null)
        {
            body.write(in.readNBytes(Integer.parseInt(contentLength.strip())));
        }
        return body.toByteArray();
    }

    /**
     * One CRLF-terminated line as ISO-8859-1; {@code null} at end of stream.
     */
    private static String readLine(final InputStream in) throws IOException
    {
        final StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) != -1)
        {
            if (b == '\n')
            {
                final int length = line.length();
                if (length > 0 && line.charAt(length - 1) == '\r')
                {
                    line.setLength(length - 1);
                }
                return line.toString();
            }
            line.append((char) b);
        }
        return line.isEmpty() ? null : line.toString();
    }
}
