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
        // built from scratch for every name outside its own table), to remove nothing. The pass
        // screens each name by length first, so an ordinary header costs a switch and at most
        // two comparisons.
        final Scan scan = new Scan();
        headers.forEach(scan, Scan::accept);

        final boolean webSocket = "websocket".equalsIgnoreCase(scan.upgrade);

        // Names the client listed in Connection are hop-by-hop by declaration. Framing and Host
        // are never removed on its say-so: dropping Content-Length or Transfer-Encoding would
        // change how the body is delimited, which is a request-smuggling primitive of its own.
        for (int i = 0; i < scan.connectionLines; i++)
        {
            removeNamedIn(scan.connection[i], headers, webSocket);
        }

        // "TE: trailers" is how a client says it accepts trailers (gRPC depends on it); any other
        // transfer coding is a matter for this connection only.
        final boolean keepTe = scan.teLines == 1 && "trailers".equalsIgnoreCase(scan.te.strip());

        for (int i = 0; i < scan.candidateCount; i++)
        {
            final boolean remove = switch (scan.kinds[i])
            {
                case HOP_BY_HOP -> true;
                case KIND_TE -> !keepTe;
                case KIND_UPGRADE -> !webSocket;
                case FORWARDING -> !trustedPeer;
                default -> false;
            };
            if (remove)
            {
                headers.remove(scan.candidates[i]);
            }
        }

        if (webSocket)
        {
            // The upstream needs to see the upgrade request to answer 101; Connection is
            // rebuilt with only the token that asks for it.
            headers.set(CONNECTION, UPGRADE);
        }
    }

    private static final int NONE = 0;
    /**
     * Connection, Keep-Alive, Proxy-Connection, and Proxy-Authorization: credentials for a proxy
     * are for this hop, and the gateway is that proxy.
     */
    private static final int HOP_BY_HOP = 1;
    private static final int KIND_TE = 2;
    private static final int KIND_UPGRADE = 3;
    /**
     * Client address and original-request claims that only a trusted proxy may make: Forwarded,
     * X-Real-IP, X-Client-IP, True-Client-IP, X-Cluster-Client-IP, the X-Forwarded-* family, and
     * X-Original-URL and X-Rewrite-URL, which IIS, Symfony and others route by in place of the
     * request line - undoing the path the gateway matched and checked.
     */
    private static final int FORWARDING = 4;

    /**
     * Which rule, if any, can remove a header of this name. Screened by length, so a name that
     * cannot be one of the few costs one switch.
     */
    private static int kindOf(final String name)
    {
        return switch (name.length())
        {
            case 2 -> name.equalsIgnoreCase(TE) ? KIND_TE : NONE;
            case 7 -> name.equalsIgnoreCase(UPGRADE) ? KIND_UPGRADE : NONE;
            case 9 -> name.equalsIgnoreCase("Forwarded") || name.equalsIgnoreCase("X-Real-IP") ? FORWARDING : NONE;
            case 10 -> name.equalsIgnoreCase(CONNECTION) || name.equalsIgnoreCase("Keep-Alive") ? HOP_BY_HOP : NONE;
            case 11 -> name.equalsIgnoreCase("X-Client-IP") ? FORWARDING : NONE;
            case 13 -> name.equalsIgnoreCase("X-Rewrite-URL") || isForwardedFamilyName(name) ? FORWARDING : NONE;
            case 14 -> name.equalsIgnoreCase("True-Client-IP") || name.equalsIgnoreCase("X-Original-URL") || isForwardedFamilyName(name) ? FORWARDING : NONE;
            case 16 -> name.equalsIgnoreCase("Proxy-Connection") ? HOP_BY_HOP
                    : isForwardedFamilyName(name) ? FORWARDING : NONE;
            case 19 -> name.equalsIgnoreCase("Proxy-Authorization") ? HOP_BY_HOP
                    : name.equalsIgnoreCase("X-Cluster-Client-IP") || isForwardedFamilyName(name) ? FORWARDING : NONE;
            default -> isForwardedFamilyName(name) ? FORWARDING : NONE;
        };
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
     * What one pass over the headers found: the values the rules depend on, and the few names a
     * rule may remove. Nothing is allocated for an ordinary request beyond this object.
     */
    private static final class Scan
    {
        private String upgrade;
        private String te;
        private int teLines;
        private String[] connection;
        private int connectionLines;
        private String[] candidates;
        private int[] kinds;
        private int candidateCount;

        void accept(final String name, final String value)
        {
            final int kind = kindOf(name);
            if (kind == NONE)
            {
                return;
            }
            if (kind == KIND_UPGRADE && upgrade == null)
            {
                upgrade = value;
            }
            else if (kind == KIND_TE && teLines++ == 0)
            {
                te = value;
            }
            else if (kind == HOP_BY_HOP && name.equalsIgnoreCase(CONNECTION))
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

            // One entry per field-line is fine: removing a name twice removes nothing the second
            // time, and repeated names are rare enough that deduplicating would cost more.
            if (candidates == null)
            {
                candidates = new String[4];
                kinds = new int[4];
            }
            else if (candidateCount == candidates.length)
            {
                candidates = java.util.Arrays.copyOf(candidates, candidateCount * 2);
                kinds = java.util.Arrays.copyOf(kinds, candidateCount * 2);
            }
            candidates[candidateCount] = name;
            kinds[candidateCount++] = kind;
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
