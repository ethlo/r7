package com.ethlo.r7.undertow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;

class UpstreamHeaderSanitizerTest
{
    private static HeaderMap headers(final String... pairs)
    {
        final HeaderMap map = new HeaderMap();
        for (int i = 0; i < pairs.length; i += 2)
        {
            map.add(new HttpString(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    @Test
    void removesHopByHopHeadersAndTheOnesConnectionNames()
    {
        final HeaderMap map = headers(
                "Connection", "keep-alive, X-Forwarded-For, X-Custom",
                "Connection", "X-Other",
                "Keep-Alive", "timeout=5",
                "Proxy-Connection", "keep-alive",
                "Proxy-Authorization", "Basic abc",
                "Upgrade", "h2c",
                "X-Custom", "1",
                "X-Other", "2",
                "Authorization", "Bearer keep-me",
                "Accept", "*/*");

        UpstreamHeaderSanitizer.sanitize(map, true);

        assertThat(map.getHeaderNames()).extracting(HttpString::toString)
                .containsExactlyInAnyOrder("Authorization", "Accept");
    }

    @Test
    void neverRemovesFramingOrHostOnTheClientsSayingSo()
    {
        final HeaderMap map = headers(
                "Connection", "Content-Length, Transfer-Encoding, Host",
                "Content-Length", "3",
                "Transfer-Encoding", "chunked",
                "Host", "example.com");

        UpstreamHeaderSanitizer.sanitize(map, false);

        assertThat(map.getFirst("Content-Length")).isEqualTo("3");
        assertThat(map.getFirst("Transfer-Encoding")).isEqualTo("chunked");
        assertThat(map.getFirst("Host")).isEqualTo("example.com");
        assertThat(map.contains("Connection")).isFalse();
    }

    @Test
    void keepsAWebSocketUpgradeAndNothingElseInConnection()
    {
        final HeaderMap map = headers(
                "Connection", "Upgrade, X-Drop",
                "Upgrade", "websocket",
                "X-Drop", "1");

        UpstreamHeaderSanitizer.sanitize(map, false);

        assertThat(map.getFirst("Upgrade")).isEqualTo("websocket");
        assertThat(map.get("Connection")).containsExactly("Upgrade");
        assertThat(map.contains("X-Drop")).isFalse();
    }

    @Test
    void keepsOnlyTeTrailers()
    {
        final HeaderMap trailers = headers("TE", "trailers");
        UpstreamHeaderSanitizer.sanitize(trailers, false);
        assertThat(trailers.getFirst("TE")).isEqualTo("trailers");

        final HeaderMap other = headers("TE", "gzip, trailers");
        UpstreamHeaderSanitizer.sanitize(other, false);
        assertThat(other.contains("TE")).isFalse();
    }

    @Test
    void dropsForwardingClaimsFromAnUntrustedPeer()
    {
        final HeaderMap map = headers(
                "X-Forwarded-For", "6.6.6.6",
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "evil.example",
                "Forwarded", "for=6.6.6.6",
                "X-Real-IP", "6.6.6.6",
                "True-Client-IP", "6.6.6.6",
                "X-Original-URL", "/admin",
                "X-Forwarded-User", "admin",
                "x-forwarded-by", "6.6.6.6",
                "Accept", "*/*");

        UpstreamHeaderSanitizer.sanitize(map, false);

        assertThat(map.getHeaderNames()).extracting(HttpString::toString).containsExactly("Accept");
    }

    @Test
    void keepsForwardingHeadersFromATrustedProxy()
    {
        final HeaderMap map = headers(
                "X-Forwarded-For", "203.0.113.9",
                "X-Forwarded-Proto", "https",
                "X-Real-IP", "203.0.113.9");

        UpstreamHeaderSanitizer.sanitize(map, true);

        assertThat(map.getFirst("X-Forwarded-For")).isEqualTo("203.0.113.9");
        assertThat(map.getFirst("X-Forwarded-Proto")).isEqualTo("https");
        assertThat(map.getFirst("X-Real-IP")).isEqualTo("203.0.113.9");
    }

    @Test
    void parsesConnectionTokensWithOptionalWhitespaceAndEmptyEntries()
    {
        final HeaderMap map = headers(
                "Connection", " ,\tX-A ,, keep-alive,X-B\t",
                "X-A", "1",
                "X-B", "2",
                "X-C", "3");

        UpstreamHeaderSanitizer.sanitize(map, true);

        assertThat(map.getHeaderNames()).extracting(HttpString::toString).containsExactly("X-C");
    }
}
