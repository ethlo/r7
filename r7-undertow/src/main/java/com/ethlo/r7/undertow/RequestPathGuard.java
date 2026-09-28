package com.ethlo.r7.undertow;

/**
 * Refuses request paths that the gateway and an upstream could read as naming different
 * resources.
 * <p>
 * Route predicates match against the decoded request path, but the proxy forwards the raw
 * URI, and the upstream then applies its own normalisation. A path such as
 * {@code /public/../admin} matches a permissive {@code /public} route here and is served as
 * {@code /admin} by nginx, Spring or Tomcat, which walks straight past whatever access
 * control guards the {@code /admin} route. Normalising and forwarding would still assume
 * the upstream normalises the same way; refusing needs no such assumption, so every
 * ambiguous form is rejected before any route is consulted.
 * <p>
 * Runs once per request on the I/O thread, so it is a single allocation-free scan.
 */
public final class RequestPathGuard
{
    private RequestPathGuard()
    {
    }

    public enum Violation
    {
        /**
         * A {@code .} or {@code ..} segment, also with path parameters ({@code ..;x}),
         * which Tomcat and Spring resolve exactly like {@code ..}.
         */
        DOT_SEGMENT,

        /**
         * A backslash, which Windows-hosted and some Java servers treat as {@code /}.
         */
        BACKSLASH,

        /**
         * A control character that survived decoding ({@code %00}, {@code %0a}, ...);
         * NUL in particular truncates the path in C-based upstreams.
         */
        CONTROL_CHARACTER,

        /**
         * Percent-encoding of {@code .}, {@code /}, {@code \} or {@code %} still present
         * after decoding: either an encoded slash Undertow leaves alone
         * ({@code ALLOW_ENCODED_SLASH=false}) or double-encoding such as {@code %252e}. An
         * upstream that decodes once more would see a separator or dot-segment that route
         * matching never saw.
         */
        RESIDUAL_ENCODING
    }

    /**
     * @param decodedPath the request path as Undertow decoded it
     * @return the first reason the path is ambiguous, or {@code null} if it is not
     */
    public static Violation check(final String decodedPath)
    {
        final int len = decodedPath.length();
        int segmentStart = 0;
        // End of the segment's name: the first ';' in the segment, or the segment's end.
        int nameEnd = -1;
        for (int i = 0; i <= len; i++)
        {
            final char c = i < len ? decodedPath.charAt(i) : '/';
            if (c == '/')
            {
                if (isDotName(decodedPath, segmentStart, nameEnd < 0 ? i : nameEnd))
                {
                    return Violation.DOT_SEGMENT;
                }
                segmentStart = i + 1;
                nameEnd = -1;
            }
            else if (c == ';')
            {
                if (nameEnd < 0)
                {
                    nameEnd = i;
                }
            }
            else if (c < 0x20 || c == 0x7f)
            {
                return Violation.CONTROL_CHARACTER;
            }
            else if (c == '\\')
            {
                return Violation.BACKSLASH;
            }
            else if (c == '%' && i + 2 < len && isEncodedSeparator(decodedPath.charAt(i + 1), decodedPath.charAt(i + 2)))
            {
                return Violation.RESIDUAL_ENCODING;
            }
        }
        return null;
    }

    private static boolean isDotName(final String path, final int from, final int to)
    {
        final int length = to - from;
        if (length == 1)
        {
            return path.charAt(from) == '.';
        }
        return length == 2 && path.charAt(from) == '.' && path.charAt(from + 1) == '.';
    }

    /**
     * Whether {@code %hl} encodes {@code .} (2E), {@code /} (2F), {@code %} (25) or
     * {@code \} (5C).
     */
    private static boolean isEncodedSeparator(final char high, final char low)
    {
        if (high == '2')
        {
            return low == '5' || low == 'e' || low == 'E' || low == 'f' || low == 'F';
        }
        return high == '5' && (low == 'c' || low == 'C');
    }
}
