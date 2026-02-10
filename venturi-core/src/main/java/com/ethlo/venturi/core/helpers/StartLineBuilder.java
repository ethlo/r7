package com.ethlo.venturi.core.helpers;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.constants.HttpStatuses;

public final class StartLineBuilder
{
    // 2KB is usually enough for even the nastiest URIs
    private static final ThreadLocal<ByteBuffer> BUFFER = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(2048));

    private static final byte[] SPACE = " ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HTTP_1_1 = " HTTP/1.1".getBytes(StandardCharsets.US_ASCII);

    /**
     * Reconstructs the Request Start-Line: {METHOD} {URI}{QUERY} HTTP/1.1
     */
    public static ByteBuffer buildRequestLine(GatewayExchange exchange)
    {
        final ByteBuffer buffer = BUFFER.get();
        buffer.clear();

        // 1. Method
        putAscii(buffer, exchange.request().method());
        buffer.put(SPACE);

        // 2. URI
        putAscii(buffer, exchange.request().uri());

        // 3. Protocol
        buffer.put(HTTP_1_1);

        buffer.flip();
        return buffer;
    }

    /**
     * Reconstructs the Response Status-Line: HTTP/1.1 {CODE} {REASON}
     */
    public static ByteBuffer buildResponseLine(GatewayExchange exchange)
    {
        final ByteBuffer buffer = BUFFER.get();
        buffer.clear();

        // 1. Protocol
        putAscii(buffer, "HTTP/1.1 ");

        // 2. Status Code
        putAscii(buffer, Integer.toString(exchange.response().status()));
        buffer.put(SPACE);

        // 3. Reason Phrase
        putAscii(buffer, HttpStatuses.getReason(exchange.response().status()));

        buffer.flip();
        return buffer;
    }

    /**
     * Efficiently puts a string into the buffer as ASCII/UTF-8 bytes
     */
    private static void putAscii(ByteBuffer buffer, CharSequence s)
    {
        final int len = s.length();
        for (int i = 0; i < len; i++)
        {
            buffer.put((byte) s.charAt(i));
        }
    }
}