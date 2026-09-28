package com.ethlo.r7.undertow.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.validation.ValidationResult;

/**
 * {@code storage.journal_security} shapes the whitelist of header names r7 journals in plain
 * text: {@code additional_safe_*_headers} adds to the built-in defaults, {@code safe_*_headers}
 * replaces them entirely. Validation here only guards the shape of those lists and the
 * conflict between the two forms — the effect of applying either is
 * {@link com.ethlo.r7.journal.JournalSecurityTest}'s concern.
 */
class JournalSecurityConfigValidationTest
{
    @Test
    void defaultsToNoAdditionsOrOverride()
    {
        final ServerConfig.JournalSecurityConfig config = new ServerConfig.JournalSecurityConfig(null, null, null, null);

        assertThat(config.additionalSafeRequestHeaders()).isEmpty();
        assertThat(config.additionalSafeResponseHeaders()).isEmpty();
        assertThat(config.safeRequestHeaders()).isEmpty();
        assertThat(config.safeResponseHeaders()).isEmpty();
        assertThat(errorsFor(config)).isEmpty();
    }

    @Test
    void additionsAloneProduceNoErrors()
    {
        final ServerConfig.JournalSecurityConfig config = new ServerConfig.JournalSecurityConfig(
                List.of("x-api-key"), List.of("x-internal-token"), null, null);

        assertThat(errorsFor(config)).isEmpty();
    }

    @Test
    void overridesAloneProduceNoErrors()
    {
        final ServerConfig.JournalSecurityConfig config = new ServerConfig.JournalSecurityConfig(
                null, null, List.of("x-only-this"), List.of("x-only-that"));

        assertThat(errorsFor(config)).isEmpty();
    }

    @Test
    void aBlankAdditionalSafeRequestHeaderIsRejected()
    {
        final ServerConfig.JournalSecurityConfig config = new ServerConfig.JournalSecurityConfig(List.of(" "), null, null, null);

        assertThat(errorsFor(config)).anyMatch(e -> e.contains("additional_safe_request_headers"));
    }

    @Test
    void aBlankAdditionalSafeResponseHeaderIsRejected()
    {
        final ServerConfig.JournalSecurityConfig config = new ServerConfig.JournalSecurityConfig(null, List.of(""), null, null);

        assertThat(errorsFor(config)).anyMatch(e -> e.contains("additional_safe_response_headers"));
    }

    @Test
    void aBlankSafeRequestHeaderIsRejected()
    {
        final ServerConfig.JournalSecurityConfig config = new ServerConfig.JournalSecurityConfig(null, null, List.of(" "), null);

        assertThat(errorsFor(config)).anyMatch(e -> e.contains("safe_request_headers"));
    }

    @Test
    void aBlankSafeResponseHeaderIsRejected()
    {
        final ServerConfig.JournalSecurityConfig config = new ServerConfig.JournalSecurityConfig(null, null, null, List.of(""));

        assertThat(errorsFor(config)).anyMatch(e -> e.contains("safe_response_headers"));
    }

    /**
     * A name that is not a valid HTTP token can never match a wire header (RFC 9110 §5.6.2),
     * so it would otherwise pass validation while silently making the entry a no-op.
     */
    @Test
    void aStructurallyInvalidAdditionalSafeRequestHeaderIsRejected()
    {
        final ServerConfig.JournalSecurityConfig config = new ServerConfig.JournalSecurityConfig(
                List.of("bad name"), null, null, null);

        assertThat(errorsFor(config)).anyMatch(e -> e.contains("additional_safe_request_headers"));
    }

    @Test
    void aColonInAHeaderNameIsRejected()
    {
        final ServerConfig.JournalSecurityConfig config = new ServerConfig.JournalSecurityConfig(
                null, null, List.of("x:y"), null);

        assertThat(errorsFor(config)).anyMatch(e -> e.contains("safe_request_headers"));
    }

    @Test
    void aNonAsciiHeaderNameIsRejected()
    {
        final ServerConfig.JournalSecurityConfig config = new ServerConfig.JournalSecurityConfig(
                null, null, null, List.of("x-\u00e9tag"));

        assertThat(errorsFor(config)).anyMatch(e -> e.contains("safe_response_headers"));
    }

    @Test
    void aStructurallyValidHeaderNameIsAccepted()
    {
        final ServerConfig.JournalSecurityConfig config = new ServerConfig.JournalSecurityConfig(
                List.of("x-tenant-id"), null, null, null);

        assertThat(errorsFor(config)).isEmpty();
    }

    @Test
    void aNullHeaderNameIsRejected()
    {
        final List<String> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        final ServerConfig.JournalSecurityConfig config = new ServerConfig.JournalSecurityConfig(
                withNull, null, null, null);

        assertThat(errorsFor(config)).anyMatch(e -> e.contains("additional_safe_request_headers"));
    }

    @Test
    void settingBothRequestFormsTogetherIsRejected()
    {
        final ServerConfig.JournalSecurityConfig config = new ServerConfig.JournalSecurityConfig(
                List.of("x-api-key"), null, List.of("x-only-this"), null);

        assertThat(errorsFor(config)).anyMatch(e -> e.contains("safe_request_headers"));
    }

    @Test
    void settingBothResponseFormsTogetherIsRejected()
    {
        final ServerConfig.JournalSecurityConfig config = new ServerConfig.JournalSecurityConfig(
                null, List.of("x-internal-token"), null, List.of("x-only-that"));

        assertThat(errorsFor(config)).anyMatch(e -> e.contains("safe_response_headers"));
    }

    /**
     * The two forms are independent per direction: overriding the request whitelist while
     * adding to the response one (or vice versa) is not a conflict.
     */
    @Test
    void theTwoFormsCanDifferByDirectionWithoutConflict()
    {
        final ServerConfig.JournalSecurityConfig config = new ServerConfig.JournalSecurityConfig(
                null, List.of("x-internal-token"), List.of("x-only-this"), null);

        assertThat(errorsFor(config)).isEmpty();
    }

    @Test
    void theStorageConfigDefaultsToNoJournalSecurityOverrides()
    {
        final ServerConfig.StorageConfig storage = new ServerConfig.StorageConfig(null, null, null, null, null);

        assertThat(storage.journalSecurity().additionalSafeRequestHeaders()).isEmpty();
        assertThat(storage.journalSecurity().safeResponseHeaders()).isEmpty();
    }

    private static List<String> errorsFor(final ServerConfig.JournalSecurityConfig config)
    {
        final ValidationResult result = new ValidationResult();
        config.validate(result);
        return result.getErrors();
    }
}
