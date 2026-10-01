package com.ethlo.r7.upstream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.ethlo.r7.api.GatewayHeaders;

/**
 * The HTTP/1.1 rules of the upstream hop: what the request head says, and what the gateway
 * accepts back. Kept apart from {@link UpstreamRelay}'s socket handling because this is where
 * smuggling and response splitting are decided, and it is tested on its own.
 * <p>
 * Parsing is strict where leniency would let the gateway and the upstream disagree about where a
 * response ends (RFC 9112 §6.3). On a pooled connection such a disagreement does not stay in one
 * response: the rest of this one is read as the head of the next, which then goes to a different
 * client. So a response whose framing is in any doubt is refused before anything is committed to
 * the client, and its connection is not reused.
 */
final class Http1
{
    static final byte[] CRLF = {'\r', '\n'};

    /**
     * Longest chunk-size line accepted, extensions included. Extensions are ignored, and nothing
     * legitimate needs more.
     */
    static final int MAX_CHUNK_LINE = 4096;

    /**
     * Most interim (1xx) responses accepted before the final one; a stream of them is a loop.
     */
    static final int MAX_INTERIM_RESPONSES = 16;

    private Http1()
    {
    }

    enum Framing
    {
        NONE, LENGTH, CHUNKED;

        /**
         * The request's framing. The pipeline has already refused any Transfer-Encoding other
         * than a lone {@code chunked}, so its presence means chunked.
         */
        static Framing ofRequest(final GatewayHeaders headers)
        {
            if (headers.getFirst("Transfer-Encoding") != null)
            {
                return CHUNKED;
            }
            return headers.getFirst("Content-Length") != null ? LENGTH : NONE;
        }
    }

    // --- Request ---------------------------------------------------------------------------------

    /**
     * The request line and headers to send upstream: the headers as the pipeline left them, with
     * Host rewritten to the target, the gateway's X-Forwarded-* added (extending a trusted
     * proxy's chain, starting one otherwise), and framing and
     * Expect left to this hop.
     */
    static byte[] requestHead(final HttpUpstream.Target target, final ProxiedExchange exchange) throws ClientProtocolException
    {
        final GatewayHeaders headers = exchange.forwardHeaders();
        final StringBuilder sb = new StringBuilder(512);
        final String requestTarget = exchange.forwardTarget();
        if (!isFieldValue(requestTarget) || requestTarget.indexOf(' ') >= 0)
        {
            throw new ClientProtocolException("The request target cannot be forwarded");
        }
        sb.append(exchange.forwardMethod()).append(' ').append(target.basePath).append(requestTarget).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(target.hostHeader).append("\r\n");
        final String originalHost = headers.getFirst("Host");
        final boolean check = !exchange.parsedAsHttp1();
        final boolean[] valid = {true};
        headers.forEach(sb, (b, name, value) ->
        {
            if (check && (!isToken(name) || !isFieldValue(value)))
            {
                valid[0] = false;
            }
            else if (!name.equalsIgnoreCase("Host") && !name.equalsIgnoreCase("Expect") && !name.equalsIgnoreCase("X-Forwarded-For")
                    && !name.equalsIgnoreCase("Content-Length") && !name.equalsIgnoreCase("Transfer-Encoding"))
            {
                b.append(name).append(": ").append(value).append("\r\n");
            }
        });
        if (!valid[0])
        {
            throw new ClientProtocolException("A request header cannot be forwarded as HTTP/1.1");
        }
        final List<String> chain = new ArrayList<>();
        for (final String value : headers.getAll("X-Forwarded-For"))
        {
            chain.add(value);
        }
        chain.add(exchange.forwardedFor());
        sb.append("X-Forwarded-For: ").append(String.join(", ", chain)).append("\r\n");
        if (headers.getFirst("X-Forwarded-Host") == null && originalHost != null)
        {
            sb.append("X-Forwarded-Host: ").append(originalHost).append("\r\n");
        }
        final String proto = headers.getFirst("X-Forwarded-Proto");
        if (proto == null)
        {
            sb.append("X-Forwarded-Proto: ").append(exchange.forwardedProto()).append("\r\n");
        }
        if (headers.getFirst("X-Forwarded-Server") == null)
        {
            sb.append("X-Forwarded-Server: ").append(hostNameOf(originalHost)).append("\r\n");
        }
        if (headers.getFirst("X-Forwarded-Port") == null)
        {
            sb.append("X-Forwarded-Port: ").append(portOf(originalHost, proto != null ? proto : exchange.forwardedProto())).append("\r\n");
        }
        if (headers.getFirst("Transfer-Encoding") != null)
        {
            sb.append("Transfer-Encoding: chunked\r\n");
        }
        else if (headers.getFirst("Content-Length") != null)
        {
            sb.append("Content-Length: ").append(requestContentLength(headers)).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * The request's declared body length, one number however it was sent ("5, 5" and repeated
     * headers included); anything that is not one non-negative decimal is refused.
     */
    static long requestContentLength(final GatewayHeaders headers) throws ClientProtocolException
    {
        long length = -1;
        try
        {
            for (final String value : headers.getAll("Content-Length"))
            {
                length = mergeContentLength(length, value);
            }
        }
        catch (final UpstreamProtocolException e)
        {
            throw new ClientProtocolException("The request's Content-Length is invalid");
        }
        return length;
    }

    static boolean isToken(final String name)
    {
        if (name.isEmpty())
        {
            return false;
        }
        for (int i = 0; i < name.length(); i++)
        {
            if (!isTokenChar(name.charAt(i)))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * No control characters other than HTAB, no DEL, nothing past ISO-8859-1: above all no CR
     * or LF, which would end the line in the HTTP/1.1 head.
     */
    static boolean isFieldValue(final String value)
    {
        for (int i = 0; i < value.length(); i++)
        {
            final char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f || c > 0xff)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * The port the client addressed, as reported in X-Forwarded-Port: the
     * Host header's, or the scheme's default.
     */
    static int portOf(final String host, final String scheme)
    {
        final int fallback = "https".equalsIgnoreCase(scheme) ? 443 : 80;
        if (host == null)
        {
            return fallback;
        }
        final int bracket = host.lastIndexOf(']');
        final int colon = host.lastIndexOf(':');
        if (colon <= bracket || colon == host.length() - 1)
        {
            return fallback;
        }
        int port = 0;
        for (int i = colon + 1; i < host.length(); i++)
        {
            final char c = host.charAt(i);
            if (c < '0' || c > '9' || port > 65535)
            {
                return fallback;
            }
            port = port * 10 + (c - '0');
        }
        return port >= 1 && port <= 65535 ? port : fallback;
    }

    /**
     * The host the client addressed, without its port, as reported in X-Forwarded-Server; an
     * IPv6 literal keeps its brackets.
     */
    static String hostNameOf(final String host)
    {
        if (host == null || host.isEmpty())
        {
            return "localhost";
        }
        final int bracket = host.lastIndexOf(']');
        final int colon = host.lastIndexOf(':');
        return colon > bracket ? host.substring(0, colon) : host;
    }

    static boolean isIdempotent(final String method)
    {
        // RFC 9110 §9.2.2.
        return switch (method)
        {
            case "GET", "HEAD", "OPTIONS", "TRACE", "PUT", "DELETE" -> true;
            default -> false;
        };
    }

    // --- Response head -----------------------------------------------------------------------------

    /**
     * An upstream response head, validated, with only the headers to relay kept.
     */
    static final class ResponseHead
    {
        int status;
        boolean http10;
        final List<String> relayed = new ArrayList<>(32);   // name, value, name, value, ...
        long contentLength = -1;
        boolean chunked;
        /**
         * The upstream asked to close, or its framing obliges it: Transfer-Encoding together with
         * Content-Length, or Transfer-Encoding on HTTP/1.0.
         */
        boolean close;
        boolean hasTransferEncoding;
        boolean closeToken;
        boolean keepAliveToken;
    }

    /**
     * Reads one response head, skipping interim 1xx responses other than 101.
     *
     * @return null when the upstream closed the connection before sending a byte of a response
     */
    static ResponseHead readResponseHead(final HttpUpstream.LineReader in, final int maxHeadBytes, final int maxHeaderCount) throws IOException
    {
        for (int interim = 0; interim <= MAX_INTERIM_RESPONSES; interim++)
        {
            final String statusLine = in.readLine(maxHeadBytes);
            if (statusLine == null)
            {
                return null;
            }
            final ResponseHead head = new ResponseHead();
            parseStatusLine(statusLine, head);
            int budget = maxHeadBytes - statusLine.length() - 2;
            int count = 0;
            final List<String> connectionTokens = new ArrayList<>(0);
            final StringBuilder transferEncoding = new StringBuilder();
            while (true)
            {
                final String line = in.readLine(Math.max(0, budget));
                if (line == null)
                {
                    throw new UpstreamProtocolException("Upstream closed the connection inside a response head");
                }
                if (line.isEmpty())
                {
                    break;
                }
                budget -= line.length() + 2;
                if (++count > maxHeaderCount)
                {
                    throw new UpstreamProtocolException("Upstream sent more than " + maxHeaderCount + " response headers");
                }
                parseHeaderLine(line, head, connectionTokens, transferEncoding);
            }
            if (head.status >= 100 && head.status < 200 && head.status != 101)
            {
                continue;
            }
            finishFraming(head, connectionTokens, transferEncoding);
            return head;
        }
        throw new UpstreamProtocolException("Upstream sent more than " + MAX_INTERIM_RESPONSES + " interim responses");
    }

    /**
     * {@code HTTP/1.x SP 3DIGIT [SP reason]}. The reason phrase is not relayed, so its content
     * does not matter; a missing space before an empty reason is tolerated, as widely sent.
     */
    static void parseStatusLine(final String line, final ResponseHead head) throws UpstreamProtocolException
    {
        if (line.length() < 12 || !line.startsWith("HTTP/1.") || line.charAt(8) != ' '
                || (line.length() > 12 && line.charAt(12) != ' '))
        {
            throw new UpstreamProtocolException("Malformed upstream status line");
        }
        final char minor = line.charAt(7);
        if (minor != '0' && minor != '1')
        {
            throw new UpstreamProtocolException("Unsupported upstream HTTP version");
        }
        head.http10 = minor == '0';
        int status = 0;
        for (int i = 9; i < 12; i++)
        {
            final char c = line.charAt(i);
            if (c < '0' || c > '9')
            {
                throw new UpstreamProtocolException("Malformed upstream status code");
            }
            status = status * 10 + (c - '0');
        }
        if (status < 100)
        {
            throw new UpstreamProtocolException("Malformed upstream status code");
        }
        head.status = status;
    }

    private static void parseHeaderLine(final String line, final ResponseHead head, final List<String> connectionTokens, final StringBuilder transferEncoding) throws UpstreamProtocolException
    {
        final char first = line.charAt(0);
        if (first == ' ' || first == '\t')
        {
            // RFC 9112 §5.2: a proxy must reject obs-fold or replace it; a continuation line
            // is also how a header is smuggled past a parser that does not know the rule.
            throw new UpstreamProtocolException("Upstream sent a folded header line");
        }
        final int colon = line.indexOf(':');
        if (colon <= 0)
        {
            throw new UpstreamProtocolException("Upstream sent a header line without a name");
        }
        for (int i = 0; i < colon; i++)
        {
            // Also rejects whitespace before the colon (RFC 9112 §5.1): "Content-Length : 5"
            // is a different header to one parser and the framing header to another.
            if (!isTokenChar(line.charAt(i)))
            {
                throw new UpstreamProtocolException("Upstream sent an invalid header name");
            }
        }
        final String name = line.substring(0, colon);
        final String value = trimOws(line, colon + 1);
        for (int i = 0; i < value.length(); i++)
        {
            final char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f)
            {
                throw new UpstreamProtocolException("Upstream sent a control character in header " + name);
            }
        }

        if (name.equalsIgnoreCase("Content-Length"))
        {
            head.contentLength = mergeContentLength(head.contentLength, value);
        }
        else if (name.equalsIgnoreCase("Transfer-Encoding"))
        {
            head.hasTransferEncoding = true;
            if (!transferEncoding.isEmpty())
            {
                transferEncoding.append(',');
            }
            transferEncoding.append(value);
        }
        else if (name.equalsIgnoreCase("Connection"))
        {
            addConnectionTokens(value, head, connectionTokens);
        }
        else if (!isHopByHop(name))
        {
            head.relayed.add(name);
            head.relayed.add(value);
        }
    }

    /**
     * Splits a Connection value into tokens without allocating for the two that almost every
     * response carries, {@code keep-alive} and {@code close}, which are recorded as flags.
     */
    private static void addConnectionTokens(final String value, final ResponseHead head, final List<String> tokens)
    {
        int start = 0;
        final int length = value.length();
        while (start < length)
        {
            int end = value.indexOf(',', start);
            if (end < 0)
            {
                end = length;
            }
            int from = start;
            int to = end;
            while (from < to && (value.charAt(from) == ' ' || value.charAt(from) == '\t'))
            {
                from++;
            }
            while (to > from && (value.charAt(to - 1) == ' ' || value.charAt(to - 1) == '\t'))
            {
                to--;
            }
            final int n = to - from;
            if (n == 5 && value.regionMatches(true, from, "close", 0, 5))
            {
                head.closeToken = true;
            }
            else if (n == 10 && value.regionMatches(true, from, "keep-alive", 0, 10))
            {
                head.keepAliveToken = true;
            }
            else if (n > 0)
            {
                tokens.add(value.substring(from, to));
            }
            start = end + 1;
        }
    }

    /**
     * Every Content-Length, and every member of a comma-separated one, must be the same
     * non-negative decimal (RFC 9112 §6.3, item 5); anything else is framing to refuse.
     */
    static long mergeContentLength(final long previous, final String value) throws UpstreamProtocolException
    {
        long merged = previous;
        for (final String member : value.split(",", -1))
        {
            final String digits = member.trim();
            if (digits.isEmpty() || digits.length() > 18)
            {
                throw new UpstreamProtocolException("Upstream sent an invalid Content-Length");
            }
            long length = 0;
            for (int i = 0; i < digits.length(); i++)
            {
                final char c = digits.charAt(i);
                if (c < '0' || c > '9')
                {
                    throw new UpstreamProtocolException("Upstream sent an invalid Content-Length");
                }
                length = length * 10 + (c - '0');
            }
            if (merged != -1 && merged != length)
            {
                throw new UpstreamProtocolException("Upstream sent conflicting Content-Length values");
            }
            merged = length;
        }
        return merged;
    }

    private static void finishFraming(final ResponseHead head, final List<String> connectionTokens, final StringBuilder transferEncoding) throws UpstreamProtocolException
    {
        if (head.hasTransferEncoding)
        {
            // Only a lone "chunked" is relayed. Any other coding would still be applied to the
            // body the client gets, without the header that says so; and chunked applied twice,
            // or not last, is framing two parsers read differently.
            if (!transferEncoding.toString().trim().equalsIgnoreCase("chunked"))
            {
                throw new UpstreamProtocolException("Upstream sent a Transfer-Encoding other than chunked");
            }
            // RFC 9112 §6.3, item 3: Transfer-Encoding overrides Content-Length. The two together
            // are a smuggling signal, so the connection carries nothing after this response.
            if (head.contentLength != -1)
            {
                head.close = true;
            }
            head.chunked = true;
            head.contentLength = -1;
        }

        for (final String token : connectionTokens)
        {
            // Headers the upstream nominated as hop-by-hop for this hop only.
            for (int i = head.relayed.size() - 2; i >= 0; i -= 2)
            {
                if (head.relayed.get(i).equalsIgnoreCase(token))
                {
                    head.relayed.remove(i + 1);
                    head.relayed.remove(i);
                }
            }
        }
        if (head.closeToken || (head.http10 && !head.keepAliveToken))
        {
            head.close = true;
        }
        if (head.http10 && head.hasTransferEncoding)
        {
            head.close = true;
        }
    }

    /**
     * Response headers that describe this hop and are never relayed. Connection,
     * Transfer-Encoding and Content-Length are handled as framing.
     */
    static boolean isHopByHop(final String name)
    {
        return name.equalsIgnoreCase("Keep-Alive") || name.equalsIgnoreCase("Proxy-Connection")
                || name.equalsIgnoreCase("TE") || name.equalsIgnoreCase("Trailer") || name.equalsIgnoreCase("Upgrade");
    }

    // --- Chunked body ------------------------------------------------------------------------------

    /**
     * {@code chunk-size [chunk-ext]}: one or more hex digits, then nothing, or optional whitespace
     * and a {@code ;} starting extensions, which are ignored. Refuses signs, a leading {@code 0x},
     * whitespace before the digits and sizes past a long - each read by some parser as a different
     * size than by another.
     */
    static long parseChunkSize(final String line) throws UpstreamProtocolException
    {
        int i = 0;
        long size = 0;
        final int length = line.length();
        while (i < length)
        {
            final int digit = hexValue(line.charAt(i));
            if (digit < 0)
            {
                break;
            }
            if (i >= 15)
            {
                throw new UpstreamProtocolException("Upstream sent an oversized chunk size");
            }
            size = (size << 4) | digit;
            i++;
        }
        if (i == 0)
        {
            throw new UpstreamProtocolException("Upstream sent a malformed chunk size");
        }
        while (i < length && (line.charAt(i) == ' ' || line.charAt(i) == '\t'))
        {
            i++;
        }
        if (i < length && line.charAt(i) != ';')
        {
            throw new UpstreamProtocolException("Upstream sent a malformed chunk size");
        }
        return size;
    }

    // --- Characters ----------------------------------------------------------------------------------

    /**
     * ASCII hex only: {@link Character#digit} also accepts non-ASCII digits.
     */
    private static int hexValue(final char c)
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

    static boolean isTokenChar(final char c)
    {
        // RFC 9110 §5.6.2 tchar.
        if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9')
        {
            return true;
        }
        return switch (c)
        {
            case '!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~' -> true;
            default -> false;
        };
    }

    private static String trimOws(final String line, final int from)
    {
        int start = from;
        int end = line.length();
        while (start < end && (line.charAt(start) == ' ' || line.charAt(start) == '\t'))
        {
            start++;
        }
        while (end > start && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t'))
        {
            end--;
        }
        return line.substring(start, end);
    }
}
