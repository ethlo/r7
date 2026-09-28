package com.ethlo.r7.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class JournalSecurityTest
{
    @Test
    void noAdditionsOrOverrideReturnsTheBuiltInRequestPolicy()
    {
        final HeaderNameSet resolved = JournalSecurity.resolveSafeRequestHeaders(List.of(), List.of());
        assertThat(resolved.names()).isEqualTo(JournalSecurity.SAFE_REQUEST_HEADERS.names());
    }

    @Test
    void noAdditionsOrOverrideReturnsTheBuiltInResponsePolicy()
    {
        final HeaderNameSet resolved = JournalSecurity.resolveSafeResponseHeaders(List.of(), List.of());
        assertThat(resolved.names()).isEqualTo(JournalSecurity.SAFE_RESPONSE_HEADERS.names());
    }

    @Test
    void anAdditionalSafeHeaderIsKeptOnTopOfTheDefaults()
    {
        final HeaderNameSet resolved = JournalSecurity.resolveSafeRequestHeaders(List.of("x-api-key"), List.of());
        assertThat(resolved.contains("x-api-key")).isTrue();
        assertThat(resolved.contains("user-agent")).isTrue();
    }

    @Test
    void anAdditionalSafeResponseHeaderDoesNotAffectTheRequestPolicy()
    {
        final HeaderNameSet resolved = JournalSecurity.resolveSafeResponseHeaders(List.of("x-internal-token"), List.of());
        assertThat(resolved.contains("x-internal-token")).isTrue();
        assertThat(JournalSecurity.resolveSafeRequestHeaders(List.of(), List.of()).contains("x-internal-token")).isFalse();
    }

    /**
     * There is deliberately no way to remove a name from the built-in list without an explicit
     * override — a header not on the (built-in plus additions) whitelist is always
     * fingerprinted.
     */
    @Test
    void anythingNotOnTheWhitelistIsFingerprintedRegardless()
    {
        final HeaderNameSet resolved = JournalSecurity.resolveSafeRequestHeaders(List.of("x-api-key"), List.of());
        assertThat(resolved.contains("authorization")).isFalse();
        assertThat(resolved.contains("cookie")).isFalse();
    }

    @Test
    void additionsAreCaseInsensitive()
    {
        final HeaderNameSet resolved = JournalSecurity.resolveSafeRequestHeaders(List.of("X-API-KEY"), List.of());
        assertThat(resolved.contains("x-api-key")).isTrue();
    }

    /**
     * A non-empty override replaces the built-in whitelist entirely — a built-in default not
     * repeated in the override must no longer be considered safe.
     */
    @Test
    void aNonEmptyOverrideReplacesTheBuiltInRequestWhitelistEntirely()
    {
        final HeaderNameSet resolved = JournalSecurity.resolveSafeRequestHeaders(List.of(), List.of("x-only-this"));
        assertThat(resolved.contains("x-only-this")).isTrue();
        assertThat(resolved.contains("user-agent")).isFalse();
        assertThat(resolved.contains("host")).isFalse();
    }

    @Test
    void aNonEmptyOverrideReplacesTheBuiltInResponseWhitelistEntirely()
    {
        final HeaderNameSet resolved = JournalSecurity.resolveSafeResponseHeaders(List.of(), List.of("x-only-this"));
        assertThat(resolved.contains("x-only-this")).isTrue();
        assertThat(resolved.contains("server")).isFalse();
        assertThat(resolved.contains("content-type")).isFalse();
    }

    @Test
    void overrideNamesAreCaseInsensitiveToo()
    {
        final HeaderNameSet resolved = JournalSecurity.resolveSafeRequestHeaders(List.of(), List.of("X-Only-This"));
        assertThat(resolved.contains("x-only-this")).isTrue();
    }

    /**
     * Locks in the built-in request-header additions: mechanism headers, range requests,
     * fixed-vocabulary Fetch Metadata and Client Hints, and other non-sensitive metadata.
     * A regression here silently starts fingerprinting a header operators expect in plain
     * text, or the reverse.
     */
    @Test
    void theBuiltInRequestWhitelistIncludesTheStandardSafeHeaders()
    {
        assertThat(JournalSecurity.SAFE_REQUEST_HEADERS.names()).contains(
                "transfer-encoding", "priority",
                "range", "expect", "max-forwards", "upgrade-insecure-requests", "early-data",
                "sec-fetch-dest", "sec-fetch-user",
                "sec-ch-ua-arch", "sec-ch-ua-bitness", "sec-ch-ua-full-version", "sec-ch-ua-full-version-list",
                "sec-ch-ua-model", "sec-ch-ua-platform-version", "sec-ch-ua-wow64",
                "sec-ch-prefers-color-scheme", "sec-ch-prefers-reduced-motion", "save-data");
    }

    /**
     * X-Requested-With is conventionally set to "XMLHttpRequest" by frameworks, but is not a
     * fixed-vocabulary header: client-side script can set it to anything, so it stays
     * fingerprinted by default rather than being treated like the true fixed-vocabulary
     * headers above.
     */
    @Test
    void xRequestedWithIsNotSafeByDefault()
    {
        assertThat(JournalSecurity.SAFE_REQUEST_HEADERS.contains("x-requested-with")).isFalse();
    }

    /**
     * As above, for the built-in response-header additions.
     */
    @Test
    void theBuiltInResponseWhitelistIncludesTheStandardSafeHeaders()
    {
        assertThat(JournalSecurity.SAFE_RESPONSE_HEADERS.names()).contains(
                "transfer-encoding", "priority",
                "content-range", "accept-ranges", "last-modified", "allow",
                "referrer-policy", "x-xss-protection", "x-permitted-cross-domain-policies",
                "cross-origin-opener-policy", "cross-origin-embedder-policy", "cross-origin-resource-policy");
    }

    /**
     * Server-Timing allows server-defined metric names plus arbitrary quoted descriptions, and
     * X-Runtime's value is unconstrained; unlike the fixed-vocabulary policy headers above,
     * either could carry internal detail. Redaction does not validate value grammar, so these
     * stay fingerprinted by default.
     */
    @Test
    void serverTimingAndXRuntimeAreNotSafeByDefault()
    {
        assertThat(JournalSecurity.SAFE_RESPONSE_HEADERS.contains("server-timing")).isFalse();
        assertThat(JournalSecurity.SAFE_RESPONSE_HEADERS.contains("x-runtime")).isFalse();
    }
}
