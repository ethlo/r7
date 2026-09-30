package com.ethlo.r7.server;

import com.ethlo.r7.api.MutableGatewayHeaders;

/**
 * Removes the client headers that must not reach an upstream as the client wrote them.
 * <p>
 * A proxy copies every inbound request header to the upstream request, so without this step two
 * kinds of header pass straight through:
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
 * the journal still see what the client actually sent. The headers must match names ignoring
 * case, as every server's request header view does.
 */
public final class UpstreamHeaderSanitizer
{
    private static final String CONNECTION = "Connection";
    private static final String UPGRADE = "Upgrade";
    private static final String TE = "TE";

    /**
     * Client address and original-request claims that only a trusted proxy may make.
     * {@code X-Original-URL} and {@code X-Rewrite-URL} are included because IIS, Symfony and
     * others route by them in place of the request line, which would undo the path the gateway
     * matched and checked.
     */
    private static final String[] FORWARDING_HEADERS = {
            "Forwarded",
            "X-Real-IP",
            "X-Client-IP",
            "True-Client-IP",
            "X-Cluster-Client-IP",
            "X-Original-URL",
            "X-Rewrite-URL"
    };

    /**
     * Every {@code X-Forwarded-*} header is a claim about an earlier hop, including extensions
     * such as {@code X-Forwarded-User} that some backends trust for identity, so the whole
     * family is matched by prefix rather than listed. The separators are checked individually
     * as {@code -} or {@code _} rather than as one literal string: CGI-derived environments
     * (classic CGI, FastCGI, and frameworks that read headers out of a CGI-style environment,
     * e.g. some PHP and WSGI/Rack stacks) fold a header name to an environment variable by
     * turning every non-alphanumeric character, {@code -} included, into {@code _}. Such a
     * backend reads {@code X_Forwarded_For} exactly as it would read {@code X-Forwarded-For}, so
     * a client sending the underscore (or mixed) form would otherwise sail past a check written
     * only against hyphens and have its forged claim believed downstream.
     */
    private static final String FORWARDED = "forwarded";

    /**
     * Length of {@code X-Forwarded-}: 1 ({@code x}) + 1 (separator) + 9 ({@code forwarded}) + 1
     * (separator), the shortest a name in the family can be before whatever it forwards.
     */
    private static final int X_FORWARDED_PREFIX_LENGTH = 1 + 1 + FORWARDED.length() + 1;

    private UpstreamHeaderSanitizer()
    {
    }

    public static void sanitize(final MutableGatewayHeaders headers, final boolean trustedPeer)
    {
        final boolean webSocket = "websocket".equalsIgnoreCase(headers.getFirst(UPGRADE));

        // Names the client listed in Connection are hop-by-hop by declaration. Framing and Host
        // are never removed on its say-so: dropping Content-Length or Transfer-Encoding would
        // change how the body is delimited, which is a request-smuggling primitive of its own.
        // The lines are copied first: removing a header they name (Connection itself, even) must
        // not disturb the iteration, and not every container gives a stable view under removal.
        final String[] connection = valuesOf(headers, CONNECTION);
        if (connection != null)
        {
            for (final String line : connection)
            {
                removeNamedIn(line, headers, webSocket);
            }
        }

        headers.remove(CONNECTION);
        headers.remove("Keep-Alive");
        headers.remove("Proxy-Connection");
        // Credentials for a proxy are for this hop: the gateway is that proxy.
        headers.remove("Proxy-Authorization");

        // "TE: trailers" is how a client says it accepts trailers (gRPC depends on it); any other
        // transfer coding is a matter for this connection only.
        final String te = headers.getFirst(TE);
        if (te != null && !(count(headers, TE) == 1 && "trailers".equalsIgnoreCase(te.strip())))
        {
            headers.remove(TE);
        }

        if (webSocket)
        {
            // The upstream needs to see the upgrade request to answer 101; Connection is
            // rebuilt with only the token that asks for it.
            headers.set(CONNECTION, UPGRADE);
        }
        else
        {
            headers.remove(UPGRADE);
        }

        if (!trustedPeer)
        {
            for (final String name : FORWARDING_HEADERS)
            {
                headers.remove(name);
            }
            removeXForwardedFamily(headers);
        }
    }

    /**
     * The field-lines of {@code name}, copied; {@code null} when there are none, so an ordinary
     * request without the header allocates nothing.
     */
    private static String[] valuesOf(final MutableGatewayHeaders headers, final String name)
    {
        String[] values = null;
        int n = 0;
        for (final String value : headers.getAll(name))
        {
            if (values == null)
            {
                values = new String[2];
            }
            else if (n == values.length)
            {
                values = java.util.Arrays.copyOf(values, n * 2);
            }
            values[n++] = value;
        }
        return values == null || n == values.length ? values : java.util.Arrays.copyOf(values, n);
    }

    private static int count(final MutableGatewayHeaders headers, final String name)
    {
        int n = 0;
        for (final String ignored : headers.getAll(name))
        {
            n++;
        }
        return n;
    }

    /**
     * Removes the headers a Connection field line names. Scans the comma-separated list in place:
     * this runs before every proxied request, and the usual tokens ({@code keep-alive},
     * {@code close}, {@code upgrade}) are handled without allocating - only a token naming some
     * other header costs the substring needed to remove it.
     */
    private static void removeNamedIn(final String line, final MutableGatewayHeaders headers, final boolean webSocket)
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
     * Collects matches before removing them, since a container cannot be changed while it is
     * being iterated; the array is only allocated once a match is found, which an ordinary
     * request from an untrusted client never has.
     */
    private static void removeXForwardedFamily(final MutableGatewayHeaders headers)
    {
        final Matches matches = new Matches();
        headers.forEach(matches, (m, name, value) ->
        {
            if (isForwardedFamilyName(name))
            {
                m.add(name);
            }
        });
        for (int i = 0; i < matches.count; i++)
        {
            headers.remove(matches.names[i]);
        }
    }

    private static final class Matches
    {
        private String[] names;
        private int count;

        void add(final String name)
        {
            if (names == null)
            {
                names = new String[4];
            }
            else if (count == names.length)
            {
                names = java.util.Arrays.copyOf(names, count * 2);
            }
            names[count++] = name;
        }
    }

    /**
     * Whether {@code name} is {@code X-Forwarded-*} in any casing, with either {@code -} or
     * {@code _} (independently) at the two separator positions - see {@link #FORWARDED} for why
     * both must be accepted. A name that is not even long enough to be a candidate costs
     * nothing beyond the length check.
     */
    static boolean isForwardedFamilyName(final String name)
    {
        if (name.length() <= X_FORWARDED_PREFIX_LENGTH || !isCharIgnoreCase(name.charAt(0), 'x') || !isSeparator(name.charAt(1)))
        {
            return false;
        }
        for (int i = 0; i < FORWARDED.length(); i++)
        {
            if (!isCharIgnoreCase(name.charAt(2 + i), FORWARDED.charAt(i)))
            {
                return false;
            }
        }
        return isSeparator(name.charAt(X_FORWARDED_PREFIX_LENGTH - 1));
    }

    private static boolean isSeparator(final char c)
    {
        return c == '-' || c == '_';
    }

    /**
     * ASCII-only case-insensitive compare: header names are ASCII, and folding the 0x20 bit this
     * way is only valid for letters, which is all {@link #FORWARDED} and the leading {@code x}
     * ever are. A char above 0xFF can never match, so it is not folded into one that does.
     */
    private static boolean isCharIgnoreCase(final char c, final char lower)
    {
        return c <= 0xFF && (char) (c | 0x20) == lower;
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
        return regionIs(line, from, to, "Host")
                || regionIs(line, from, to, "Content-Length")
                || regionIs(line, from, to, "Transfer-Encoding")
                || (webSocket && regionIs(line, from, to, UPGRADE));
    }
}
