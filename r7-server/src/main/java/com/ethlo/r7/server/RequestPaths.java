package com.ethlo.r7.server;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Turns the raw request path into the decoded path that route predicates match and
 * {@link RequestPathGuard} checks.
 * <p>
 * Decoded, never normalised. A server that resolves {@code .} and {@code ..} segments before
 * r7 sees the path (Helidon's {@code UriPath.path()} does) hands the predicates {@code /kit/x}
 * for a request whose raw target, {@code /kit/./x}, is what the proxy forwards - so the route
 * matched and the path guard approved one resource while the upstream resolves another. Decoding
 * the raw path here keeps the dot segments in view, where the guard refuses them.
 * <p>
 * Percent-escapes are decoded as UTF-8, except an encoded {@code /}: it stays encoded, so the
 * guard sees it as
 * residual encoding rather than as a separator the upstream never received. A malformed escape is
 * kept as it was sent. A server whose decoded path already has these properties need not use this.
 */
public final class RequestPaths
{
    private RequestPaths()
    {
    }

    /**
     * The path of a request target in absolute form (RFC 9112 §3.2.2, {@code GET http://host/p}),
     * which a server must accept; any other target is returned as it is. Servlet containers reduce
     * the absolute form themselves, Helidon hands it over whole - and a route
     * prefix never matches {@code http://...}. Only the scheme and authority are dropped: the
     * path after them is kept as sent, double slashes and escapes included.
     */
    public static String originForm(final String target)
    {
        if (target.isEmpty() || target.charAt(0) == '/')
        {
            return target;
        }
        final int scheme = target.indexOf("://");
        if (scheme <= 0)
        {
            return target;
        }
        final int path = target.indexOf('/', scheme + 3);
        return path < 0 ? "/" : target.substring(path);
    }

    public static String decode(final String rawPath)
    {
        if (rawPath.indexOf('%') < 0)
        {
            return rawPath;
        }
        final StringBuilder out = new StringBuilder(rawPath.length());
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final int len = rawPath.length();
        int i = 0;
        while (i < len)
        {
            final char c = rawPath.charAt(i);
            if (c == '%' && i + 2 < len && hex(rawPath.charAt(i + 1)) >= 0 && hex(rawPath.charAt(i + 2)) >= 0)
            {
                final int b = hex(rawPath.charAt(i + 1)) << 4 | hex(rawPath.charAt(i + 2));
                if (b == '/')
                {
                    flush(bytes, out);
                    out.append(rawPath, i, i + 3);
                }
                else
                {
                    bytes.write(b);
                }
                i += 3;
            }
            else
            {
                flush(bytes, out);
                out.append(c);
                i++;
            }
        }
        flush(bytes, out);
        return out.toString();
    }

    private static void flush(final ByteArrayOutputStream bytes, final StringBuilder out)
    {
        if (bytes.size() > 0)
        {
            out.append(bytes.toString(StandardCharsets.UTF_8));
            bytes.reset();
        }
    }

    private static int hex(final char c)
    {
        if (c >= '0' && c <= '9')
        {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f')
        {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F')
        {
            return c - 'A' + 10;
        }
        return -1;
    }
}
