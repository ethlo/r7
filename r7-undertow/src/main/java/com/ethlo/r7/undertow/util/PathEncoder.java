package com.ethlo.r7.undertow.util;

import java.nio.charset.StandardCharsets;

/**
 * Percent-encodes a decoded path back into the form that may appear on a request line.
 * <p>
 * Filters see and set the <em>decoded</em> path, but the proxy writes the request URI to the
 * upstream verbatim. Handing it a decoded path unencoded turns characters the client sent as
 * data into URI syntax: {@code %3F} becomes a real {@code ?} (query injection), {@code %23}
 * a fragment, {@code %25} a {@code %} the upstream decodes a second time, and {@code %20} a
 * space that splits the request line.
 * <p>
 * Everything outside RFC 3986 {@code pchar} and {@code /} is encoded as UTF-8, the charset
 * Undertow decoded the path with, so a path that is only stripped or rewritten round-trips to
 * the bytes the client sent.
 */
public final class PathEncoder
{
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private PathEncoder()
    {
    }

    /**
     * @return {@code decodedPath} itself when nothing needs encoding, which is the common case
     */
    public static String encode(final String decodedPath)
    {
        final int len = decodedPath.length();
        int i = 0;
        while (i < len && isAllowed(decodedPath.charAt(i)))
        {
            i++;
        }
        if (i == len)
        {
            return decodedPath;
        }

        final byte[] bytes = decodedPath.substring(i).getBytes(StandardCharsets.UTF_8);
        final StringBuilder sb = new StringBuilder(len + 2 * bytes.length);
        sb.append(decodedPath, 0, i);
        for (final byte b : bytes)
        {
            final char c = (char) (b & 0xFF);
            if (c < 0x80 && isAllowed(c))
            {
                sb.append(c);
            }
            else
            {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
            }
        }
        return sb.toString();
    }

    /**
     * RFC 3986 §3.3 {@code pchar} (unreserved, sub-delims, {@code :} and {@code @}) plus the
     * segment separator {@code /}.
     */
    private static boolean isAllowed(final char c)
    {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))
        {
            return true;
        }
        return switch (c)
        {
            case '-', '.', '_', '~', '!', '$', '&', '\'', '(', ')', '*', '+', ',', ';', '=', ':', '@', '/' -> true;
            default -> false;
        };
    }
}
