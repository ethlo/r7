package com.ethlo.r7.undertow.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

/**
 * Durations that R7Main and R7UndertowHandler hand to Undertow/XNIO as int milliseconds. Past
 * that range the conversion throws (or, for a plain cast, silently wraps), so validation has to
 * refuse the value first and say which field to change.
 */
class DurationLimitsValidationTest
{
    private static final Duration AT_LIMIT = ValidatorUtils.MAX_INT_MILLIS;
    private static final Duration BEYOND_LIMIT = ValidatorUtils.MAX_INT_MILLIS.plusMillis(1);

    @Test
    void proxyMaxRequestTime()
    {
        assertThat(errorsFor(new ServerConfig.ProxyConfig(null, null, AT_LIMIT, null))).isEmpty();
        assertRefusedNaming(new ServerConfig.ProxyConfig(null, null, BEYOND_LIMIT, null), "max_request_time");
    }

    @Test
    void proxyTtl()
    {
        assertThat(errorsFor(new ServerConfig.ProxyConfig(null, null, null, AT_LIMIT))).isEmpty();
        assertRefusedNaming(new ServerConfig.ProxyConfig(null, null, null, BEYOND_LIMIT), "ttl");
    }

    @Test
    void proxyTtlOfMinusOneStillMeansNoLimit()
    {
        assertThat(errorsFor(new ServerConfig.ProxyConfig(null, null, null, Duration.ofMillis(-1)))).isEmpty();
    }

    @Test
    void httpRequestParseTimeout()
    {
        assertThat(errorsFor(new ServerConfig.HttpConfig(null, AT_LIMIT, null))).isEmpty();
        assertRefusedNaming(new ServerConfig.HttpConfig(null, BEYOND_LIMIT, null), "request_parse_timeout");
    }

    @Test
    void advancedSocketReadTimeout()
    {
        assertThat(errorsFor(new ServerConfig.AdvancedConfig(null, null, null, null, null, null, null, AT_LIMIT))).isEmpty();
        assertRefusedNaming(new ServerConfig.AdvancedConfig(null, null, null, null, null, null, null, BEYOND_LIMIT), "socket_read_timeout");
    }

    @Test
    void theErrorIsReportedUnderTheFullPathFromTheServerConfigRoot()
    {
        final ServerConfig config = new ServerConfig(null, null, null, new ServerConfig.ProxyConfig(null, null, null, Duration.ofDays(30)), null, null, null);
        final ValidationResult result = new ValidationResult();
        config.validate(result);
        assertThat(result.getErrors()).singleElement().asString().contains("proxy.ttl").contains("24d");
    }

    @Test
    void theDefaultsAreAccepted()
    {
        final ValidationResult result = new ValidationResult();
        ServerConfig.standard().validate(result);
        assertThat(result.getErrors()).isEmpty();
    }

    private static void assertRefusedNaming(final ValidatableConfig config, final String field)
    {
        assertThat(errorsFor(config)).singleElement().asString()
                .contains(field)
                .contains(String.valueOf(Integer.MAX_VALUE));
    }

    private static List<String> errorsFor(final ValidatableConfig config)
    {
        final ValidationResult result = new ValidationResult();
        config.validate(result);
        return result.getErrors();
    }
}
