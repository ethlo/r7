package com.ethlo.r7.core.helpers;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.QueryParams;

class StartLineBuilderTest
{
    private static GatewayRequest request(final String uri, final String query)
    {
        final QueryParams queryParams = mock(QueryParams.class);
        when(queryParams.toQueryString()).thenReturn(query);
        final GatewayRequest request = mock(GatewayRequest.class);
        when(request.method()).thenReturn("GET");
        when(request.uri()).thenReturn(uri);
        when(request.queryParams()).thenReturn(queryParams);
        when(request.protocol()).thenReturn("HTTP/1.1");
        return request;
    }

    private static String asString(final ByteBuffer buffer)
    {
        final byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return new String(bytes, US_ASCII);
    }

    @Test
    void buildsAnOrdinaryRequestLine()
    {
        assertThat(asString(StartLineBuilder.buildRequestLine(request("/a/b", "x=1")))).isEqualTo("GET /a/b?x=1 HTTP/1.1");
        assertThat(asString(StartLineBuilder.buildRequestLine(request("/a/b", "")))).isEqualTo("GET /a/b HTTP/1.1");
    }

    /**
     * The default max_header_size admits request lines of up to 8KB, and a rewritten path grows
     * when percent-encoded, so a line longer than the 2KB per-thread buffer must not overflow it.
     */
    @Test
    void buildsARequestLineLongerThanThePerThreadBuffer()
    {
        final String uri = "/" + "%C3%A9".repeat(1000);
        final String query = "q=" + "x".repeat(2000);

        final String line = asString(StartLineBuilder.buildRequestLine(request(uri, query)));

        assertThat(line).isEqualTo("GET " + uri + "?" + query + " HTTP/1.1");
    }

    @Test
    void anOversizedLineDoesNotDisturbTheNextOrdinaryOne()
    {
        StartLineBuilder.buildRequestLine(request("/" + "a".repeat(5000), null));

        assertThat(asString(StartLineBuilder.buildRequestLine(request("/short", null)))).isEqualTo("GET /short HTTP/1.1");
    }
}
