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
        final ByteBuffer buffer = BUFFER.get();
        buffer.clear();

        // 1. Method
        putAscii(buffer, request.method());
        buffer.put(SPACE);

        // 2. URI and Query String
        putAscii(buffer, request.uri());

        final String queryString = request.queryParams().toQueryString();
        if (queryString != null && !queryString.isEmpty())
        {
            buffer.put((byte) '?');
            putAscii(buffer, queryString);
        }

        buffer.put(SPACE);

        // 3. Protocol
        putAscii(buffer, request.protocol());

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