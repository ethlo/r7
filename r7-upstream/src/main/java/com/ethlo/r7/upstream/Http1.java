package com.ethlo.r7.upstream;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.ethlo.r7.api.GatewayHeaders;

/**
 * The HTTP/1.1 rules of the upstream hop, with no I/O: what the request head says and how the
 * body is framed. Kept apart from {@link UpstreamRelay}'s socket handling because this is where
 * smuggling and response splitting are decided, and it is to be tested on its own
 * (design/upstream.md, step 2).
 */
final class Http1
{
    static final byte[] CRLF = {'\r', '\n'};

    private Http1()
    {
    }

    enum Framing
    {
        NONE, LENGTH, CHUNKED;

        /**
         * The request's framing. The pipeline has already refused any Transfer-Encoding other
         * than a lone {@code chunked}, so its presence means chunked.
         */
        static Framing ofRequest(final GatewayHeaders headers)
        {
            if (headers.getFirst("Transfer-Encoding") != null)
            {
                return CHUNKED;
            }
            return headers.getFirst("Content-Length") != null ? LENGTH : NONE;
        }
    }

    /**
     * The request line and headers to send upstream: the headers as the pipeline left them, with
     * Host rewritten to the target, the gateway's X-Forwarded-* added the way Undertow's proxy
     * adds them (extending a trusted proxy's chain, starting one otherwise), and framing and
     * Expect left to this hop.
     */
    static byte[] requestHead(final HttpUpstream.Target target, final ProxiedExchange exchange)
    {
        final GatewayHeaders headers = exchange.forwardHeaders();
        final StringBuilder sb = new StringBuilder(512);
        sb.append(exchange.forwardMethod()).append(' ').append(target.basePath).append(exchange.forwardTarget()).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(target.hostHeader).append("\r\n");
        final String originalHost = headers.getFirst("Host");
        headers.forEach(sb, (b, name, value) ->
        {
            if (!name.equalsIgnoreCase("Host") && !name.equalsIgnoreCase("Expect") && !name.equalsIgnoreCase("X-Forwarded-For")
                    && !name.equalsIgnoreCase("Content-Length") && !name.equalsIgnoreCase("Transfer-Encoding"))
            {
                b.append(name).append(": ").append(value).append("\r\n");
            }
        });
        final List<String> chain = new ArrayList<>();
        for (final String value : headers.getAll("X-Forwarded-For"))
        {
            chain.add(value);
        }
        chain.add(exchange.forwardedFor());
        sb.append("X-Forwarded-For: ").append(String.join(", ", chain)).append("\r\n");
        if (headers.getFirst("X-Forwarded-Host") == null && originalHost != null)
        {
            sb.append("X-Forwarded-Host: ").append(originalHost).append("\r\n");
        }
        if (headers.getFirst("X-Forwarded-Proto") == null)
        {
            sb.append("X-Forwarded-Proto: ").append(exchange.forwardedProto()).append("\r\n");
        }
        final String contentLength = headers.getFirst("Content-Length");
        if (headers.getFirst("Transfer-Encoding") != null)
        {
            sb.append("Transfer-Encoding: chunked\r\n");
        }
        else if (contentLength != null)
        {
            sb.append("Content-Length: ").append(contentLength).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    static int statusOf(final String statusLine)
    {
        final int start = statusLine.indexOf(' ') + 1;
        return Integer.parseInt(statusLine.substring(start, start + 3));
    }

    /**
     * Response headers that describe this hop and are not relayed. Connection, Transfer-Encoding
     * and Content-Length are handled by the relay itself.
     */
    static boolean isHopByHop(final String name)
    {
        return name.equalsIgnoreCase("Keep-Alive") || name.equalsIgnoreCase("Proxy-Connection")
                || name.equalsIgnoreCase("TE") || name.equalsIgnoreCase("Trailer") || name.equalsIgnoreCase("Upgrade");
    }
}
