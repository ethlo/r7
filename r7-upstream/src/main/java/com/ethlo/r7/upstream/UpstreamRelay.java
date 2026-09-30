package com.ethlo.r7.upstream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.ethlo.r7.api.MutableGatewayResponse;
import com.ethlo.r7.core.proxy.NoAvailableTargetException;
import com.ethlo.r7.core.proxy.ProxyConnectionException;

/**
 * Runs one proxied exchange on the calling thread, which must be allowed to block: pick a target,
 * send the request head and body, read the response head, commit the client's response and relay
 * the body. The client side is whatever the server's {@link ProxiedExchange} gives it.
 */
public final class UpstreamRelay
{
    private UpstreamRelay()
    {
    }

    /**
     * @throws ProxyFailure when the exchange did not complete; the server answers the client with
     *                      its status unless the response has already started
     */
    public static void relay(final HttpUpstream upstream, final ProxiedExchange exchange, final String routeId) throws ProxyFailure
    {
        final HttpUpstream.Target target = upstream.pick();
        if (target == null)
        {
            throw new ProxyFailure(503, "No upstream available", new NoAvailableTargetException("No target is up for " + routeId));
        }
        exchange.attemptedTarget(target.uri);

        final byte[] head = Http1.requestHead(target, exchange);
        final Http1.Framing framing = Http1.Framing.ofRequest(exchange.forwardHeaders());

        HttpUpstream.Connection connection = null;
        try
        {
            connection = target.acquire();
            String[] statusAndHeaders;
            try
            {
                statusAndHeaders = exchangeHead(connection, exchange, head, framing);
            }
            catch (final IOException e)
            {
                // A pooled connection the upstream closed while it sat idle fails on first use.
                // Reconnect once, and only when there was no body: a body may be half-sent.
                if (!connection.reused || framing != Http1.Framing.NONE)
                {
                    throw e;
                }
                connection.close();
                connection = target.acquire();
                statusAndHeaders = exchangeHead(connection, exchange, head, framing);
            }
            final boolean reusable = relayResponse(connection, exchange, statusAndHeaders);
            if (reusable)
            {
                target.release(connection);
            }
            else
            {
                connection.close();
            }
            connection = null;
        }
        catch (final ConnectException e)
        {
            throw new ProxyFailure(503, "Upstream connection failed", new ProxyConnectionException("TCP Connection failed to: " + target.uri));
        }
        catch (final SocketTimeoutException e)
        {
            throw new ProxyFailure(504, "Upstream timed out", e);
        }
        catch (final RequestBodyTooLargeException e)
        {
            throw new ProxyFailure(413, "Request body too large", null);
        }
        catch (final IOException e)
        {
            throw new ProxyFailure(502, "Upstream failed", new ProxyConnectionException("Upstream exchange failed with " + target.uri + ": " + e.getMessage()));
        }
        finally
        {
            if (connection != null)
            {
                connection.close();
            }
        }
    }

    /**
     * Sends the request head and body and reads the upstream's response head, skipping any
     * interim 1xx responses. Returns the status line followed by the header lines.
     */
    private static String[] exchangeHead(final HttpUpstream.Connection connection, final ProxiedExchange exchange, final byte[] head, final Http1.Framing framing) throws IOException
    {
        final OutputStream out = connection.out;
        out.write(head);
        if (framing != Http1.Framing.NONE)
        {
            copyRequestBody(out, exchange, framing);
        }
        out.flush();

        while (true)
        {
            final String statusLine = connection.reader.readLine();
            if (statusLine == null)
            {
                throw new IOException("Upstream closed the connection before responding");
            }
            final List<String> lines = new ArrayList<>();
            lines.add(statusLine);
            String line;
            while ((line = connection.reader.readLine()) != null && !line.isEmpty())
            {
                lines.add(line);
            }
            final int status = Http1.statusOf(statusLine);
            if (status >= 100 && status < 200 && status != 101)
            {
                continue;
            }
            return lines.toArray(new String[0]);
        }
    }

    private static void copyRequestBody(final OutputStream out, final ProxiedExchange exchange, final Http1.Framing framing) throws IOException
    {
        final InputStream in = exchange.openRequestBody();
        final byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1)
        {
            exchange.onRequestBody(buffer, 0, n);
            if (framing == Http1.Framing.CHUNKED)
            {
                out.write(Integer.toHexString(n).getBytes(StandardCharsets.ISO_8859_1));
                out.write(Http1.CRLF);
                out.write(buffer, 0, n);
                out.write(Http1.CRLF);
            }
            else
            {
                out.write(buffer, 0, n);
            }
        }
        if (framing == Http1.Framing.CHUNKED)
        {
            out.write('0');
            out.write(Http1.CRLF);
            out.write(Http1.CRLF);
        }
    }

    /**
     * Relays the upstream's response to the client. Returns whether the upstream connection can
     * be reused: the body was framed and read to its end, and the upstream did not ask to close.
     */
    private static boolean relayResponse(final HttpUpstream.Connection connection, final ProxiedExchange exchange, final String[] head) throws IOException, ProxyFailure
    {
        final int status = Http1.statusOf(head[0]);
        if (status == 101)
        {
            // The upgrade request went upstream sanitised like any other; tunnelling the upgraded
            // connection is not done yet.
            connection.close();
            throw new ProxyFailure(502, "WebSocket tunnelling is not supported by this server (experimental)", null);
        }
        final MutableGatewayResponse response = exchange.clientResponse();
        response.status(status);

        String connectionHeader = null;
        String contentLength = null;
        boolean chunked = false;
        for (int i = 1; i < head.length; i++)
        {
            final String line = head[i];
            final int colon = line.indexOf(':');
            if (colon <= 0)
            {
                continue;
            }
            final String name = line.substring(0, colon).trim();
            final String value = line.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Connection"))
            {
                connectionHeader = value;
            }
            else if (name.equalsIgnoreCase("Transfer-Encoding"))
            {
                chunked = value.toLowerCase().contains("chunked");
            }
            else if (name.equalsIgnoreCase("Content-Length"))
            {
                contentLength = value;
            }
            else if (!Http1.isHopByHop(name))
            {
                response.headers().add(name, value);
            }
        }

        final boolean noBody = "HEAD".equalsIgnoreCase(exchange.forwardMethod()) || status == 204 || status == 304;
        if (!noBody && !chunked && contentLength != null)
        {
            response.headers().set("Content-Length", contentLength);
        }

        if (noBody)
        {
            exchange.commit(false);
            return connectionHeader == null || !connectionHeader.equalsIgnoreCase("close");
        }

        final HttpUpstream.LineReader in = connection.reader;
        boolean framed = true;
        try (OutputStream out = exchange.commit(true))
        {
            if (chunked)
            {
                relayChunked(in, out, exchange);
            }
            else if (contentLength != null)
            {
                relayExactly(in, out, exchange, Long.parseLong(contentLength));
            }
            else
            {
                framed = false;
                relayExactly(in, out, exchange, Long.MAX_VALUE);
            }
        }
        return framed && (connectionHeader == null || !connectionHeader.equalsIgnoreCase("close"));
    }

    private static void relayExactly(final HttpUpstream.LineReader in, final OutputStream out, final ProxiedExchange exchange, final long length) throws IOException
    {
        final byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0)
        {
            final int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n == -1)
            {
                if (length == Long.MAX_VALUE)
                {
                    return;
                }
                throw new IOException("Upstream closed the connection " + remaining + " bytes short of its Content-Length");
            }
            writeResponseBody(out, exchange, buffer, n);
            remaining -= n;
        }
    }

    private static void relayChunked(final HttpUpstream.LineReader in, final OutputStream out, final ProxiedExchange exchange) throws IOException
    {
        final byte[] buffer = new byte[8192];
        while (true)
        {
            final String sizeLine = in.readLine();
            if (sizeLine == null)
            {
                throw new IOException("Upstream closed the connection inside a chunked body");
            }
            final int semicolon = sizeLine.indexOf(';');
            final long size = Long.parseLong((semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon)).trim(), 16);
            if (size == 0)
            {
                String trailer;
                while ((trailer = in.readLine()) != null && !trailer.isEmpty())
                {
                    // Trailers are not relayed yet.
                }
                return;
            }
            long remaining = size;
            while (remaining > 0)
            {
                final int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n == -1)
                {
                    throw new IOException("Upstream closed the connection inside a chunk");
                }
                writeResponseBody(out, exchange, buffer, n);
                remaining -= n;
            }
            in.readLine();
        }
    }

    private static void writeResponseBody(final OutputStream out, final ProxiedExchange exchange, final byte[] buffer, final int n) throws IOException
    {
        exchange.onResponseBody(buffer, 0, n);
        out.write(buffer, 0, n);
    }
}
