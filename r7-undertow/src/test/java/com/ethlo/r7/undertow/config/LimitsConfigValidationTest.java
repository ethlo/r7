package com.ethlo.r7.undertow.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.validation.ValidationResult;

/**
 * {@code trusted_proxies} gates whether X-Forwarded-For/X-Real-IP are believed at all (see
 * {@code UndertowGatewayRequest.resolveRemoteAddress}), so a typo'd CIDR here must be caught at
 * startup rather than silently never matching (leaving the header trusted or distrusted by
 * accident, depending on which way the typo breaks).
 */
class LimitsConfigValidationTest
{
    @Test
    void theDefaultTrustedProxiesListIsEmpty()
    {
        assertEquals(List.of(), new ServerConfig.LimitsConfig(null, null, null, null, null, null).trustedProxies());
    }

    @Test
    void validCidrsAreAccepted()
    {
        assertEquals(List.of(), errorsFor(List.of("10.0.0.0/8", "192.168.1.1", "::1/128")));
    }

    @Test
    void aMalformedCidrIsRefusedNamingTheField()
    {
        final List<String> errors = errorsFor(List.of("not-an-ip"));

        assertEquals(1, errors.size(), () -> "expected exactly one error, got " + errors);
        final String error = errors.getFirst();
        assertTrue(error.contains("trusted_proxies"), () -> error);
        assertTrue(error.contains("not-an-ip"), () -> error);
    }

    @Test
    void aSubnetMaskOutOfRangeForTheAddressFamilyIsRefused()
    {
        assertTrue(errorsFor(List.of("10.0.0.0/33")).size() == 1);
    }

    private static List<String> errorsFor(final List<String> trustedProxies)
    {
        final ServerConfig.LimitsConfig config = new ServerConfig.LimitsConfig(null, null, null, null, null, trustedProxies);
        final ValidationResult result = new ValidationResult();
        config.validate(result);
        return result.getErrors();
    }
}
