package com.ethlo.r7.util;

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
 * the path was decoded with. This canonicalises rather than restores: the input is already
 * decoded, so a client's needless encoding of a {@code pchar} ({@code %41}, {@code %3B}) comes
 * out as the plain character. That is the same resource by RFC 3986 §6.2.2.2; what must not
 * change, and does not, is which characters are data and which are syntax.
 */
public final class PathEncoder
{
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static final boolean[] PATH = new boolean[128];
    private static final boolean[] QUERY_VALUE = new boolean[128];

    static
    {
        for (char c = 0; c < 128; c++)
        {
            final boolean unreserved = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            // RFC 3986 §3.3 pchar (unreserved, sub-delims, ':' and '@') plus the segment separator '/'.
            PATH[c] = unreserved || "!$&'()*+,;=:@/".indexOf(c) >= 0;
            // Inside a query or fragment, '&', '=', '+' and ';' are read as parameter structure by
            // virtually every server, and '?' / '#' as the start of a query or fragment by some:
            // data placed there must not be able to add a parameter or move the fragment.
            QUERY_VALUE[c] = unreserved || "!$'()*,:@/".indexOf(c) >= 0;
        }
    }

    private PathEncoder()
    {
    }

    /**
     * @return {@code decodedPath} itself when nothing needs encoding, which is the common case
     */
    public static String encode(final String decodedPath)
    {
        return encode(decodedPath, PATH);
    }

    /**
     * Encodes decoded text as data inside a query or fragment, where it must stay one value: on
     * top of what {@link #encode} encodes, {@code &}, {@code =}, {@code +} and {@code ;} are
     * encoded too.
     *
     * @return {@code decodedValue} itself when nothing needs encoding
     */
    public static String encodeQueryValue(final String decodedValue)
    {
        return encode(decodedValue, QUERY_VALUE);
    }

    private static String encode(final String decoded, final boolean[] allowed)
    {
        final int len = decoded.length();
        int i = 0;
        while (i < len && isAllowed(decoded.charAt(i), allowed))
        {
            i++;
        }
        if (i == len)
        {
            return decoded;
        }

        final byte[] bytes = decoded.substring(i).getBytes(StandardCharsets.UTF_8);
        final StringBuilder sb = new StringBuilder(len + 2 * bytes.length);
        sb.append(decoded, 0, i);
        for (final byte b : bytes)
        {
            final char c = (char) (b & 0xFF);
            if (isAllowed(c, allowed))
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

    private static boolean isAllowed(final char c, final boolean[] allowed)
    {
        return c < 128 && allowed[c];
    }
}
