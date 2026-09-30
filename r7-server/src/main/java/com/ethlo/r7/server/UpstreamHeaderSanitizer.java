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
        // One pass to see what is there, then removals for what is there and must go. Asking
        // the container to remove each of a dozen names an ordinary request never carries costs
        // a name conversion and a lookup apiece on every request (on Undertow, an HttpString
        // built from scratch for every name outside its own table), to remove nothing.
        final Scan scan = new Scan();
        headers.forEach(scan, Scan::accept);

        final boolean webSocket = "websocket".equalsIgnoreCase(scan.upgrade);

        // "TE: trailers" is how a client says it accepts trailers (gRPC depends on it); any other
        // transfer coding is a matter for this connection only.
        final boolean keepTe = scan.teLines == 1 && "trailers".equalsIgnoreCase(scan.te.strip());

        for (int i = 0; i < scan.nameCount; i++)
        {
            final String name = scan.names[i];
            if (mustRemove(name, scan.connection, scan.connectionLines, webSocket, keepTe, trustedPeer))
            {
                headers.remove(name);
            }
        }

        if (webSocket)
        {
            // The upstream needs to see the upgrade request to answer 101; Connection is
            // rebuilt with only the token that asks for it.
            headers.set(CONNECTION, UPGRADE);
        }
    }

    private static boolean mustRemove(final String name, final String[] connection, final int connectionLines,
                                      final boolean webSocket, final boolean keepTe, final boolean trustedPeer)
    {
        if (name.equalsIgnoreCase(CONNECTION)
                || name.equalsIgnoreCase("Keep-Alive")
                || name.equalsIgnoreCase("Proxy-Connection")
                // Credentials for a proxy are for this hop: the gateway is that proxy.
                || name.equalsIgnoreCase("Proxy-Authorization"))
        {
            return true;
        }
        if (name.equalsIgnoreCase(UPGRADE))
        {
            return !webSocket;
        }

        // Names the client listed in Connection are hop-by-hop by declaration. Framing and Host
        // are never removed on its say-so: dropping Content-Length or Transfer-Encoding would
        // change how the body is delimited, which is a request-smuggling primitive of its own.
        if (isNamedIn(connection, connectionLines, name, webSocket))
        {
            return true;
        }
        if (name.equalsIgnoreCase(TE))
        {
            return !keepTe;
        }

        if (!trustedPeer)
        {
            for (final String forwarding : FORWARDING_HEADERS)
            {
                if (name.equalsIgnoreCase(forwarding))
                {
                    return true;
                }
            }
            return isForwardedFamilyName(name);
        }
        return false;
    }

    /**
     * Whether a Connection field-line names {@code name} as hop-by-hop. Scans each
     * comma-separated list in place, without allocating: this runs for every header of every
     * proxied request. {@code keep-alive} and {@code close} are connection options, not names,
     * and the protected names are never taken on the client's say-so.
     */
    private static boolean isNamedIn(final String[] connection, final int lines, final String name, final boolean webSocket)
    {
        for (int l = 0; l < lines; l++)
        {
            final String line = connection[l];
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
                        && regionIs(line, from, to, name)
                        && !regionIs(line, from, to, "keep-alive")
                        && !regionIs(line, from, to, "close")
                        && !isProtected(line, from, to, webSocket))
                {
                    return true;
                }
                start = end + 1;
            }
        }
        return false;
    }

    /**
     * What one pass over the headers found: each distinct name once, in order, and the values the
     * rules depend on. Names are compared ignoring case, as the container matches them.
     */
    private static final class Scan
    {
        private String[] names = new String[16];
        private int nameCount;
        private String upgrade;
        private String te;
        private int teLines;
        private String[] connection;
        private int connectionLines;

        void accept(final String name, final String value)
        {
            if (name.equalsIgnoreCase(UPGRADE))
            {
                if (upgrade == null)
                {
                    upgrade = value;
                }
            }
            else if (name.equalsIgnoreCase(TE))
            {
                if (teLines++ == 0)
                {
                    te = value;
                }
            }
            else if (name.equalsIgnoreCase(CONNECTION))
            {
                if (connection == null)
                {
                    connection = new String[2];
                }
                else if (connectionLines == connection.length)
                {
                    connection = java.util.Arrays.copyOf(connection, connectionLines * 2);
                }
                connection[connectionLines++] = value;
            }

            for (int i = 0; i < nameCount; i++)
            {
                if (names[i].equalsIgnoreCase(name))
                {
                    return;
                }
            }
            if (nameCount == names.length)
            {
                names = java.util.Arrays.copyOf(names, nameCount * 2);
            }
            names[nameCount++] = name;
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
