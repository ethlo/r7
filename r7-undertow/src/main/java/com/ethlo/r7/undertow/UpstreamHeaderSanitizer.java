package com.ethlo.r7.undertow;

import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

/**
 * Removes the client headers that must not reach an upstream as the client wrote them.
 * <p>
 * Undertow's {@code ProxyHandler} copies every inbound request header to the upstream request,
 * so without this step two kinds of header pass straight through:
 * <ul>
 *   <li><b>Hop-by-hop headers</b> (RFC 9110 §7.6.1). They describe the client's connection to
 *   the gateway, not the gateway's to the upstream. Worse, any intermediary behind the gateway
 *   that honours {@code Connection: X-Forwarded-For, ...} deletes the headers the client names
 *   there, so a client could strip the forwarding headers the gateway itself set.</li>
 *   <li><b>Forwarding and client-identity headers</b> ({@code X-Real-IP}, {@code Forwarded},
 *   {@code X-Forwarded-*}, ...). {@link RemoteAddressResolver} already refuses to believe them
 *   from a peer that is not a trusted proxy, but the upstream never learns that and would
 *   believe them instead. They are removed for untrusted peers, so the proxy writes fresh
 *   {@code X-Forwarded-*} values, and kept for trusted ones, so the proxy extends their
 *   chain.</li>
 * </ul>
 * Runs on the live request headers after the client-request snapshot is taken, so filters and
 * the journal still see what the client actually sent.
 */
public final class UpstreamHeaderSanitizer
{
    private static final HttpString PROXY_CONNECTION = new HttpString("Proxy-Connection");

    /**
     * Client address and original-request claims that only a trusted proxy may make.
     * {@code X-Original-URL} and {@code X-Rewrite-URL} are included because IIS, Symfony and
     * others route by them in place of the request line, which would undo the path the gateway
     * matched and checked.
     */
    private static final HttpString[] FORWARDING_HEADERS = {
            Headers.FORWARDED,
            new HttpString("X-Real-IP"),
            new HttpString("X-Client-IP"),
            new HttpString("True-Client-IP"),
            new HttpString("X-Cluster-Client-IP"),
            new HttpString("X-Original-URL"),
            new HttpString("X-Rewrite-URL")
    };

    /**
     * Every {@code X-Forwarded-*} header is a claim about an earlier hop, including extensions
     * such as {@code X-Forwarded-User} that some backends trust for identity, so the whole
     * family is matched by prefix rather than listed.
     */
    private static final String X_FORWARDED_PREFIX = "X-Forwarded-";

    private UpstreamHeaderSanitizer()
    {
    }

    public static void sanitize(final HeaderMap headers, final boolean trustedPeer)
    {
        final boolean webSocket = "websocket".equalsIgnoreCase(headers.getFirst(Headers.UPGRADE));

        // Names the client listed in Connection are hop-by-hop by declaration. Framing and Host
        // are never removed on its say-so: dropping Content-Length or Transfer-Encoding would
        // change how the body is delimited, which is a request-smuggling primitive of its own.
        final HeaderValues connection = headers.get(Headers.CONNECTION);
        if (connection != null)
        {
            for (int i = 0; i < connection.size(); i++)
            {
                removeNamedIn(connection.get(i), headers, webSocket);
            }
        }

        headers.remove(Headers.CONNECTION);
        headers.remove(Headers.KEEP_ALIVE);
        headers.remove(PROXY_CONNECTION);
        // Credentials for a proxy are for this hop: the gateway is that proxy.
        headers.remove(Headers.PROXY_AUTHORIZATION);

        // "TE: trailers" is how a client says it accepts trailers (gRPC depends on it); any other
        // transfer coding is a matter for this connection only.
        final String te = headers.getFirst(Headers.TE);
        if (te != null && !(headers.count(Headers.TE) == 1 && "trailers".equalsIgnoreCase(te.strip())))
        {
            headers.remove(Headers.TE);
        }

        if (webSocket)
        {
            // The upstream needs to see the upgrade request to answer 101; Connection is
            // rebuilt with only the token that asks for it.
            headers.put(Headers.CONNECTION, Headers.UPGRADE_STRING);
        }
        else
        {
            headers.remove(Headers.UPGRADE);
        }

        if (!trustedPeer)
        {
            for (final HttpString name : FORWARDING_HEADERS)
            {
                headers.remove(name);
            }
            removeXForwardedFamily(headers);
        }
    }

    /**
     * Removes the headers a Connection field line names. Scans the comma-separated list in place:
     * this runs before every proxied request, and the usual tokens ({@code keep-alive},
     * {@code close}, {@code upgrade}) are handled without allocating - only a token naming some
     * other header costs the substring needed to remove it.
     */
    private static void removeNamedIn(final String line, final HeaderMap headers, final boolean webSocket)
    {
        final int len = line.length();
        int start = 0;
        while (start < len)
        {
            int end = line.indexOf(',', start);
            if (end < 0)
            {
                end = len;
            }
            int from = start;
            int to = end;
            while (from < to && isOws(line.charAt(from)))
            {
                from++;
            }
            while (to > from && isOws(line.charAt(to - 1)))
            {
                to--;
            }
            if (to > from
                    && !regionIs(line, from, to, "keep-alive")
                    && !regionIs(line, from, to, "close")
                    && !isProtected(line, from, to, webSocket))
            {
                headers.remove(line.substring(from, to));
            }
            start = end + 1;
        }
    }

    /**
     * Collects matches before removing them, since the map cannot be changed while it is being
     * iterated; the array is only allocated once a match is found, which an ordinary request
     * from an untrusted client never has.
     */
    private static void removeXForwardedFamily(final HeaderMap headers)
    {
        HttpString[] matches = null;
        int count = 0;
        for (long cookie = headers.fastIterateNonEmpty(); cookie != -1L; cookie = headers.fiNextNonEmpty(cookie))
        {
            final HttpString name = headers.fiCurrent(cookie).getHeaderName();
            if (name.length() > X_FORWARDED_PREFIX.length() && name.toString().regionMatches(true, 0, X_FORWARDED_PREFIX, 0, X_FORWARDED_PREFIX.length()))
            {
                if (matches == null)
                {
                    matches = new HttpString[headers.size()];
                }
                matches[count++] = name;
            }
        }
        for (int i = 0; i < count; i++)
        {
            headers.remove(matches[i]);
        }
    }

    private static boolean isOws(final char c)
    {
        return c == ' ' || c == '\t';
    }

    private static boolean regionIs(final String line, final int from, final int to, final String token)
    {
        return to - from == token.length() && line.regionMatches(true, from, token, 0, token.length());
    }

    private static boolean isProtected(final String line, final int from, final int to, final boolean webSocket)
    {
        return regionIs(line, from, to, Headers.HOST_STRING)
                || regionIs(line, from, to, Headers.CONTENT_LENGTH_STRING)
                || regionIs(line, from, to, Headers.TRANSFER_ENCODING_STRING)
                || (webSocket && regionIs(line, from, to, Headers.UPGRADE_STRING));
    }
}
