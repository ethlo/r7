package com.ethlo.r7.upstream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

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

        final UpstreamOptions options = upstream.options();
        final byte[] head = Http1.requestHead(target, exchange);
        final Http1.Framing framing = Http1.Framing.ofRequest(exchange.forwardHeaders());
        final Attempt attempt = new Attempt();

        HttpUpstream.Connection connection = null;
        try
        {
            connection = target.acquire();
            Http1.ResponseHead response;
            try
            {
                response = exchangeHead(connection, exchange, head, framing, options, attempt);
            }
            catch (final IOException e)
            {
                if (!mayRetry(connection, exchange, framing, attempt, e))
                {
                    throw e;
                }
                connection.close();
                connection = target.connect();
                attempt.requestFlushed = false;
                response = exchangeHead(connection, exchange, head, framing, options, attempt);
            }
            final boolean reusable = relayResponse(connection, exchange, response, options);
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
     * What the first try got as far as, which decides whether a second is safe.
     */
    private static final class Attempt
    {
        boolean requestFlushed;
    }

    /**
     * Whether a failure on a pooled connection may be retried on a fresh one. The usual cause is
     * benign - the upstream closed the connection while it sat idle - but from here that cannot be
     * told apart from an upstream that received the request, acted on it, and then failed. So a
     * request is sent twice only when doing so is harmless (RFC 9110 §9.2.2), or when it never
     * left the gateway. A request with a body is never retried: the body has been consumed.
     */
    private static boolean mayRetry(final HttpUpstream.Connection connection, final ProxiedExchange exchange, final Http1.Framing framing, final Attempt attempt, final IOException failure)
    {
        if (!connection.reused || framing != Http1.Framing.NONE || failure instanceof UpstreamProtocolException)
        {
            return false;
        }
        return !attempt.requestFlushed || Http1.isIdempotent(exchange.forwardMethod());
    }

    /**
     * Sends the request head and body and reads the upstream's final response head.
     */
    private static Http1.ResponseHead exchangeHead(final HttpUpstream.Connection connection, final ProxiedExchange exchange, final byte[] head,
                                                   final Http1.Framing framing, final UpstreamOptions options, final Attempt attempt) throws IOException
    {
        final OutputStream out = connection.out;
        try
        {
            out.write(head);
            if (framing != Http1.Framing.NONE)
            {
                copyRequestBody(out, exchange, framing);
            }
            out.flush();
            attempt.requestFlushed = true;
        }
        catch (final ClientBodyException | RequestBodyTooLargeException e)
        {
            throw e;
        }
        catch (final IOException e)
        {
            if (framing == Http1.Framing.NONE)
            {
                throw e;
            }
            // The upstream stopped reading the body. It may have answered first - 413 is the
            // usual reason to stop - and then its answer is what the client should get, not 502.
            final Http1.ResponseHead early = earlyResponse(connection, options);
            if (early == null)
            {
                throw e;
            }
            return early;
        }

        final Http1.ResponseHead response = Http1.readResponseHead(connection.reader, options.maxHeadBytes(), options.maxHeaderCount());
        if (response == null)
        {
            throw new IOException("Upstream closed the connection before responding");
        }
        return response;
    }

    private static Http1.ResponseHead earlyResponse(final HttpUpstream.Connection connection, final UpstreamOptions options)
    {
        try
        {
            final Http1.ResponseHead early = Http1.readResponseHead(connection.reader, options.maxHeadBytes(), options.maxHeaderCount());
            if (early != null)
            {
                // The request was not fully sent, so nothing after this response can be trusted.
                early.close = true;
            }
            return early;
        }
        catch (final IOException ignored)
        {
            return null;
        }
    }

    /**
     * A read of the client's request body failed: the client went away or sent a broken body. Kept
     * apart from a failed write upstream, which may mean the upstream has already answered.
     */
    private static final class ClientBodyException extends IOException
    {
        ClientBodyException(final IOException cause)
        {
            super("Reading the client's request body failed: " + cause.getMessage(), cause);
        }
    }

    private static void copyRequestBody(final OutputStream out, final ProxiedExchange exchange, final Http1.Framing framing) throws IOException
    {
        final InputStream in = exchange.openRequestBody();
        final byte[] buffer = new byte[8192];
        while (true)
        {
            final int n;
            try
            {
                n = in.read(buffer);
            }
            catch (final IOException e)
            {
                throw new ClientBodyException(e);
            }
            if (n == -1)
            {
                break;
            }
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
        // Only reached when the whole body was read: a failure above leaves a chunked body
        // without its terminating chunk, and the socket is then closed, so the upstream sees a
        // truncated request rather than a complete one.
        if (framing == Http1.Framing.CHUNKED)
        {
            out.write('0');
            out.write(Http1.CRLF);
            out.write(Http1.CRLF);
        }
    }

    /**
     * Relays the upstream's response to the client. Returns whether the upstream connection can
     * be reused: the body was framed and read to its end, and nothing obliges a close.
     */
    private static boolean relayResponse(final HttpUpstream.Connection connection, final ProxiedExchange exchange, final Http1.ResponseHead head, final UpstreamOptions options) throws IOException, ProxyFailure
    {
        if (head.status == 101)
        {
            // The upgrade request went upstream sanitised like any other; tunnelling the upgraded
            // connection is not done yet.
            connection.close();
            throw new ProxyFailure(502, "WebSocket tunnelling is not supported by this server (experimental)", null);
        }
        final MutableGatewayResponse response = exchange.clientResponse();
        response.status(head.status);
        for (int i = 0; i < head.relayed.size(); i += 2)
        {
            response.headers().add(head.relayed.get(i), head.relayed.get(i + 1));
        }

        final boolean noBody = "HEAD".equalsIgnoreCase(exchange.forwardMethod()) || head.status == 204 || head.status == 304;
        if (noBody)
        {
            exchange.commit(false);
            return !head.close;
        }
        if (!head.chunked && head.contentLength >= 0)
        {
            response.headers().set("Content-Length", Long.toString(head.contentLength));
        }

        final HttpUpstream.LineReader in = connection.reader;
        final OutputStream out = exchange.commit(true);
        final boolean reusable;
        try
        {
            if (head.chunked)
            {
                relayChunked(in, out, exchange, options);
                reusable = !head.close;
            }
            else if (head.contentLength >= 0)
            {
                relayExactly(in, out, exchange, head.contentLength);
                reusable = !head.close;
            }
            else
            {
                // Neither length nor chunked: the body runs to the close (RFC 9112 §6.3, item 8).
                relayExactly(in, out, exchange, Long.MAX_VALUE);
                reusable = false;
            }
        }
        catch (final IOException | RuntimeException e)
        {
            // Not out.close(): that would complete the client's message around a partial body.
            exchange.abortResponse();
            throw e;
        }
        out.close();
        return reusable;
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

    private static void relayChunked(final HttpUpstream.LineReader in, final OutputStream out, final ProxiedExchange exchange, final UpstreamOptions options) throws IOException
    {
        final byte[] buffer = new byte[8192];
        while (true)
        {
            final String sizeLine = in.readLine(Http1.MAX_CHUNK_LINE);
            if (sizeLine == null)
            {
                throw new UpstreamProtocolException("Upstream closed the connection inside a chunked body");
            }
            final long size = Http1.parseChunkSize(sizeLine);
            if (size == 0)
            {
                skipTrailers(in, options);
                return;
            }
            long remaining = size;
            while (remaining > 0)
            {
                final int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n == -1)
                {
                    throw new UpstreamProtocolException("Upstream closed the connection inside a chunk");
                }
                writeResponseBody(out, exchange, buffer, n);
                remaining -= n;
            }
            // Exactly CRLF after the data: anything else means the chunk was longer than its
            // size said, and the excess would be read as the next chunk's size.
            final String end = in.readLine(0);
            if (end == null)
            {
                throw new UpstreamProtocolException("Upstream closed the connection inside a chunked body");
            }
        }
    }

    /**
     * Trailers are not relayed yet, but they are read to the end so the connection can be reused,
     * under the same bounds as a response head.
     */
    private static void skipTrailers(final HttpUpstream.LineReader in, final UpstreamOptions options) throws IOException
    {
        int budget = options.maxHeadBytes();
        for (int count = 0; ; count++)
        {
            final String trailer = in.readLine(Math.max(0, budget));
            if (trailer == null)
            {
                throw new UpstreamProtocolException("Upstream closed the connection inside chunked trailers");
            }
            if (trailer.isEmpty())
            {
                return;
            }
            if (count >= options.maxHeaderCount())
            {
                throw new UpstreamProtocolException("Upstream sent more than " + options.maxHeaderCount() + " trailers");
            }
            budget -= trailer.length() + 2;
        }
    }

    private static void writeResponseBody(final OutputStream out, final ProxiedExchange exchange, final byte[] buffer, final int n) throws IOException
    {
        exchange.onResponseBody(buffer, 0, n);
        out.write(buffer, 0, n);
    }
}
