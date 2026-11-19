package com.ethlo.r7.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;


class UpstreamHeaderSanitizerTest
{
    private static TestHeaders headers(final String... pairs)
    {
        return TestHeaders.of(pairs);
    }

    @Test
    void removesHopByHopHeadersAndTheOnesConnectionNames()
    {
        final TestHeaders map = headers(
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

        assertThat(map.names())
                .containsExactlyInAnyOrder("Authorization", "Accept");
    }

    @Test
    void neverRemovesFramingOrHostOnTheClientsSayingSo()
    {
        final TestHeaders map = headers(
                "Connection", "Content-Length, Transfer-Encoding, Host",
                "Content-Length", "3",
                "Transfer-Encoding", "chunked",
                "Host", "example.com");

        UpstreamHeaderSanitizer.sanitize(map, false);

        assertThat(map.getFirst("Content-Length")).isEqualTo("3");
        assertThat(map.getFirst("Transfer-Encoding")).isEqualTo("chunked");
        assertThat(map.getFirst("Host")).isEqualTo("example.com");
        assertThat(map.getFirst("Connection")).isNull();
    }

    @Test
    void keepsAWebSocketUpgradeAndNothingElseInConnection()
    {
        final TestHeaders map = headers(
                "Connection", "Upgrade, X-Drop",
                "Upgrade", "websocket",
                "X-Drop", "1");

        UpstreamHeaderSanitizer.sanitize(map, false);

        assertThat(map.getFirst("Upgrade")).isEqualTo("websocket");
        assertThat(map.getAll("Connection")).containsExactly("Upgrade");
        assertThat(map.getFirst("X-Drop")).isNull();
    }

    @Test
    void keepsOnlyTeTrailers()
    {
        final TestHeaders trailers = headers("TE", "trailers");
        UpstreamHeaderSanitizer.sanitize(trailers, false);
        assertThat(trailers.getFirst("TE")).isEqualTo("trailers");

        final TestHeaders other = headers("TE", "gzip, trailers");
        UpstreamHeaderSanitizer.sanitize(other, false);
        assertThat(other.getFirst("TE")).isNull();
    }

    @Test
    void dropsForwardingClaimsFromAnUntrustedPeer()
    {
        final TestHeaders map = headers(
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

        assertThat(map.names()).containsExactly("Accept");
    }

    /**
     * CGI-derived environments (and frameworks that read headers out of a CGI-style environment)
     * fold every non-alphanumeric character in a header name, including {@code -}, to {@code _}
     * before an application ever sees it - so such a backend reads {@code X_Forwarded_For}
     * exactly as it would read {@code X-Forwarded-For}. A client that sends the underscore (or
     * a mixed-separator) form must not sail past a prefix check written only against hyphens.
     */
    @Test
    void dropsUnderscoreAndMixedSeparatorForwardedVariantsFromAnUntrustedPeer()
    {
        final TestHeaders map = headers(
                "X_Forwarded_For", "6.6.6.6",
                "X-Forwarded_Proto", "https",
                "X_Forwarded-Host", "evil.example",
                "Accept", "*/*");

        UpstreamHeaderSanitizer.sanitize(map, false);

        assertThat(map.names()).containsExactly("Accept");
    }

    @Test
    void keepsForwardingHeadersFromATrustedProxy()
    {
        final TestHeaders map = headers(
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
        final TestHeaders map = headers(
                "Connection", " ,\tX-A ,, keep-alive,X-B\t",
                "X-A", "1",
                "X-B", "2",
                "X-C", "3");

        UpstreamHeaderSanitizer.sanitize(map, true);

        assertThat(map.names()).containsExactly("X-C");
    }
}
