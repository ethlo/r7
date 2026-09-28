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
            Headers.X_FORWARDED_FOR,
            Headers.X_FORWARDED_HOST,
            Headers.X_FORWARDED_PROTO,
            Headers.X_FORWARDED_PORT,
            Headers.X_FORWARDED_SERVER,
            new HttpString("X-Forwarded-Prefix"),
            new HttpString("X-Forwarded-Ssl"),
            new HttpString("X-Real-IP"),
            new HttpString("X-Client-IP"),
            new HttpString("True-Client-IP"),
            new HttpString("X-Cluster-Client-IP"),
            new HttpString("X-Original-URL"),
            new HttpString("X-Rewrite-URL")
    };

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
            for (final String line : connection.toArray())
            {
                for (final String token : line.split(","))
                {
                    final String name = token.strip();
                    if (!name.isEmpty() && !isProtected(name, webSocket))
                    {
                        headers.remove(name);
                    }
                }
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
        }
    }

    private static boolean isProtected(final String name, final boolean webSocket)
    {
        return name.equalsIgnoreCase(Headers.HOST_STRING)
                || name.equalsIgnoreCase(Headers.CONTENT_LENGTH_STRING)
                || name.equalsIgnoreCase(Headers.TRANSFER_ENCODING_STRING)
                || (webSocket && name.equalsIgnoreCase(Headers.UPGRADE_STRING));
    }
}
