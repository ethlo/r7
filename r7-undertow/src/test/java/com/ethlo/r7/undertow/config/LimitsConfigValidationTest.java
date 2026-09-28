package com.ethlo.r7.undertow.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.validation.ValidationResult;

/**
 * {@code trusted_proxies} gates whether X-Forwarded-For/X-Real-IP are believed at all (see
 * {@code RemoteAddressResolver}), so a typo'd CIDR here must be caught at startup rather than
 * silently never matching (leaving the header trusted or distrusted by accident, depending on
 * which way the typo breaks).
 */
class LimitsConfigValidationTest
{
    @Test
    void theDefaultTrustedProxiesListIsEmpty()
    {
        assertThat(new ServerConfig.LimitsConfig(null, null, null, null, null, null).trustedProxies()).isEmpty();
    }

    @Test
    void validCidrsAreAccepted()
    {
        assertThat(errorsFor(List.of("10.0.0.0/8", "192.168.1.1", "::1/128"))).isEmpty();
    }

    @Test
    void aMalformedCidrIsRefusedNamingTheField()
    {
        final List<String> errors = errorsFor(List.of("not-an-ip"));

        assertThat(errors).hasSize(1);
        final String error = errors.getFirst();
        assertThat(error).contains("trusted_proxies").contains("not-an-ip");
    }

    @Test
    void aSubnetMaskOutOfRangeForTheAddressFamilyIsRefused()
    {
        assertThat(errorsFor(List.of("10.0.0.0/33"))).hasSize(1);
    }

    private static List<String> errorsFor(final List<String> trustedProxies)
    {
        final ServerConfig.LimitsConfig config = new ServerConfig.LimitsConfig(null, null, null, null, null, trustedProxies);
        final ValidationResult result = new ValidationResult();
        config.validate(result);
        return result.getErrors();
    }
}
