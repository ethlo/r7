package com.ethlo.r7.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestPathsTest
{
    @Test
    void decodesWithoutNormalising()
    {
        assertThat(RequestPaths.decode("/kit/./x")).isEqualTo("/kit/./x");
        assertThat(RequestPaths.decode("/kit/%2e%2E/admin")).isEqualTo("/kit/../admin");
        assertThat(RequestPaths.decode("/caf%C3%A9")).isEqualTo("/café");
    }

    @Test
    void keepsAnEncodedSlashEncodedSoTheGuardSeesIt()
    {
        assertThat(RequestPaths.decode("/kit/a%2fb")).isEqualTo("/kit/a%2fb");
        assertThat(RequestPathGuard.check(RequestPaths.decode("/kit/a%2Fb"))).isEqualTo(RequestPathGuard.Violation.RESIDUAL_ENCODING);
    }

    @Test
    void decodesOnceSoDoubleEncodingStaysVisible()
    {
        assertThat(RequestPaths.decode("/kit/%252e%252e")).isEqualTo("/kit/%2e%2e");
        assertThat(RequestPathGuard.check(RequestPaths.decode("/kit/%252e%252e/x"))).isEqualTo(RequestPathGuard.Violation.RESIDUAL_ENCODING);
    }

    @Test
    void keepsMalformedEscapesAsSent()
    {
        assertThat(RequestPaths.decode("/a%zz/%4")).isEqualTo("/a%zz/%4");
        assertThat(RequestPaths.decode("/plain")).isEqualTo("/plain");
    }
}
