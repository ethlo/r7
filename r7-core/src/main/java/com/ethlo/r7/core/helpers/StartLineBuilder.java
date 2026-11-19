package com.ethlo.r7.core.helpers;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.GatewayResponse;
import com.ethlo.r7.util.constants.HttpStatuses;

public final class StartLineBuilder
{
    // 2KB is usually enough for even the nastiest URIs
    private static final ThreadLocal<ByteBuffer> BUFFER = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(2048));

    private static final byte[] SPACE = " ".getBytes(StandardCharsets.US_ASCII);

    /**
     * Reconstructs the Request Start-Line: {METHOD} {URI}{?QUERY} {PROTOCOL}
     */
    public static ByteBuffer buildRequestLine(final GatewayRequest request)
    {
        final String method = request.method();
        final String uri = request.uri();
        final String queryString = request.queryParams().toQueryString();
        final String protocol = request.protocol();
        final boolean hasQuery = queryString != null && !queryString.isEmpty();

        // The request line is bounded by the listener's max_header_size (8KB by default), not by
        // this buffer, and a rewritten path grows further once percent-encoded. A line that does
        // not fit gets a one-off buffer of its own size rather than a BufferOverflowException that
        // fails the request; the per-thread buffer is not grown, so one long URI does not pin a
        // large direct buffer to every thread that ever served one.
        final int required = length(method) + length(uri) + (hasQuery ? 1 + queryString.length() : 0) + length(protocol) + 2 * SPACE.length;
        final ByteBuffer threadBuffer = BUFFER.get();
        final ByteBuffer buffer = required <= threadBuffer.capacity() ? threadBuffer : ByteBuffer.allocate(required);
        buffer.clear();

        // 1. Method
        putAscii(buffer, method);
        buffer.put(SPACE);

        // 2. URI and Query String
        putAscii(buffer, uri);

        if (hasQuery)
        {
            buffer.put((byte) '?');
            putAscii(buffer, queryString);
        }

        buffer.put(SPACE);

        // 3. Protocol
        putAscii(buffer, protocol);

        buffer.flip();
        return buffer;
    }

    /**
     * Reconstructs the Response Status-Line: {PROTOCOL} {CODE} {REASON}
     */
    public static ByteBuffer buildResponseLine(final String protocol, final GatewayResponse response)
    {
        final ByteBuffer buffer = BUFFER.get();
        buffer.clear();

        // 1. Protocol
        putAscii(buffer, protocol);
        buffer.put(SPACE);

        // 2. Status Code
        putAscii(buffer, Integer.toString(response.status()));
        buffer.put(SPACE);

        // 3. Reason Phrase
        putAscii(buffer, HttpStatuses.getReason(response.status()));

        buffer.flip();
        return buffer;
    }

    private static int length(final String s)
    {
        return s == null ? 0 : s.length();
    }

    /**
     * Efficiently puts a string into the buffer as ASCII/UTF-8 bytes
     */
    private static void putAscii(final ByteBuffer buffer, final String s)
    {
        if (s == null)
        {
            return;
        }

        final int len = s.length();
        for (int i = 0; i < len; i++)
        {
            buffer.put((byte) s.charAt(i));
        }
    }
}