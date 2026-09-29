package com.ethlo.r7.undertow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidationResult;

class ManagementConfigTest
{
    /**
     * The endpoint is unauthenticated, so without configuration it must not be reachable from
     * other hosts.
     */
    @Test
    void bindsToLoopbackByDefault()
    {
        assumeTrue(System.getenv(ServerConfig.ManagementConfig.HOST_ENVIRONMENT_VARIABLE) == null,
                "R7_MANAGEMENT_HOST is set in this environment");

        assertThat(new ServerConfig.ManagementConfig(null, null).host()).isEqualTo("127.0.0.1");
        assertThat(ServerConfig.standard().management().host()).isEqualTo("127.0.0.1");
    }

    @Test
    void anExplicitHostWins()
    {
        assertThat(new ServerConfig.ManagementConfig("10.0.0.5", null).host()).isEqualTo("10.0.0.5");
    }

    /**
     * Every management connection is bounded: none of the timeouts can be turned off and the
     * connection cap cannot be removed.
     */
    @Test
    void timeoutsAndTheConnectionCapMustBePositive()
    {
        final ServerConfig.ManagementConfig config = new ServerConfig.ManagementConfig(null, null,
                Duration.ZERO, Duration.ofMillis(-1), Duration.ZERO, 0, List.of(" "));
        assertThat(errorsFor(config)).hasSize(5)
                .anySatisfy(e -> assertThat(e).contains("request_parse_timeout"))
                .anySatisfy(e -> assertThat(e).contains("read_timeout"))
                .anySatisfy(e -> assertThat(e).contains("idle_timeout"))
                .anySatisfy(e -> assertThat(e).contains("max_connections"))
                .anySatisfy(e -> assertThat(e).contains("allowed_hosts"));
    }

    @Test
    void timeoutsMustFitIntMilliseconds()
    {
        final Duration beyond = ValidatorUtils.MAX_INT_MILLIS.plusMillis(1);
        assertThat(errorsFor(new ServerConfig.ManagementConfig(null, null, beyond, null, null, null, null)))
                .singleElement().asString().contains("request_parse_timeout");
        assertThat(errorsFor(new ServerConfig.ManagementConfig(null, null, null, null, ValidatorUtils.MAX_INT_MILLIS, null, null))).isEmpty();
    }

    @Test
    void theDefaultsBoundEveryConnection()
    {
        final ServerConfig.ManagementConfig config = new ServerConfig.ManagementConfig(null, null);
        assertThat(errorsFor(config)).isEmpty();
        assertThat(config.requestParseTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(config.readTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.idleTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.maxConnections()).isEqualTo(64);
        assertThat(config.allowedHosts()).isEmpty();
    }

    @Test
    void connectionLowWaterMayNotExceedHighWater()
    {
        final ValidationResult result = new ValidationResult();
        new ServerConfig.AdvancedConfig(null, null, 100, 200, null, null, null, null).validate(result);
        assertThat(result.getErrors()).singleElement().asString().contains("connection_high_water");
    }

    private static List<String> errorsFor(final ServerConfig.ManagementConfig config)
    {
        final ValidationResult result = new ValidationResult();
        config.validate(result);
        return result.getErrors().stream().map(String::valueOf).toList();
    }
}
