package com.ethlo.r7.warc;

import com.ethlo.r7.api.GatewayHeaders;

/**
 * Reconstructs a {@code WARC-Target-URI} from a captured HTTP request start-line and headers.
 * <p>
 * The gateway does not record whether the original connection was TLS-terminated, so the scheme
 * is always guessed as {@code http://} — a known, documented approximation. If no {@code Host}
 * header was captured (e.g. HEADERS/METADATA journal levels below what's needed, or a malformed
 * request), a synthetic {@code urn:r7:request:<id>} is used instead so every record still has a
 * syntactically valid target URI, per WARC 1.1 §5.6.
 */
final class TargetUri
{
    private TargetUri()
    {
    }

    static String build(final String requestStartLine, final GatewayHeaders requestHeaders, final String requestId)
    {
        final String path = extractPath(requestStartLine);
        final String host = requestHeaders != null ? requestHeaders.getFirst("Host") : null;
        if (host == null || host.isBlank())
        {
            return "urn:r7:request:" + requestId;
        }
        return "http://" + host + (path != null ? path : "/");
    }

    private static String extractPath(final String requestStartLine)
    {
        if (requestStartLine == null)
        {
            return null;
        }
        // "METHOD /path HTTP/1.1"
        final int firstSpace = requestStartLine.indexOf(' ');
        if (firstSpace < 0)
        {
            return null;
        }
        final int secondSpace = requestStartLine.indexOf(' ', firstSpace + 1);
        if (secondSpace < 0)
        {
            return requestStartLine.substring(firstSpace + 1);
        }
        return requestStartLine.substring(firstSpace + 1, secondSpace);
    }
}
