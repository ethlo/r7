package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.spi.EngineContext;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidationResult;

/**
 * The read timeout becomes an int-millisecond socket timeout when the route's upstream client is
 * built. A longer value has to be refused here, naming the field, rather than by
 * {@code Math.toIntExact} at that point: a stack trace at startup, or a hot reload rejected
 * without saying which value was wrong.
 */
class TimeoutConfigTest
{
    @TempDir
    Path dir;

    @Test
    void aReadTimeoutOfExactlyTheIntMillisecondLimitIsAccepted()
    {
        assertThat(errorsFor(ValidatorUtils.MAX_INT_MILLIS)).isEmpty();
    }

    @Test
    void aReadTimeoutBeyondTheIntMillisecondLimitIsRefusedNamingTheFieldAndTheBound()
    {
        assertThat(errorsFor(ValidatorUtils.MAX_INT_MILLIS.plusMillis(1))).singleElement().asString()
                .contains("read")
                .contains(String.valueOf(Integer.MAX_VALUE))
                .contains("24d");
    }

    @Test
    void theDefaultReadTimeoutIsAccepted()
    {
        assertThat(errorsFor(null)).isEmpty();
    }

    @Test
    void aRouteWithAThirtyDayReadTimeoutFailsToBuildNamingTheField() throws IOException
    {
        final Path file = this.dir.resolve("routes.yaml");
        Files.writeString(file, """
                version: test
                routes:
                  - id: slow
                    match:
                      - PathPrefix:
                          prefix: /slow
                    upstream:
                      targets:
                        - url: http://localhost:1
                      timeouts:
                        read: 30d
                """);
        final RoutesDefinition definition = ConfigurationManager.load(file, RoutesDefinition.class);
        final ConfigurationManager manager = new ConfigurationManager(new EngineContext(Map.of()));

        assertThatThrownBy(() -> manager.build(definition))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("timeouts.read")
                .hasMessageContaining("24d");
    }

    @Test
    void aConnectTimeoutBeyondTheIntMillisecondLimitIsRefusedNamingTheField()
    {
        assertThat(connectErrorsFor(ValidatorUtils.MAX_INT_MILLIS.plusMillis(1))).singleElement().asString()
                .contains("connect")
                .contains("24d");
    }

    @Test
    void aConnectTimeoutBelowOneMillisecondIsRefused()
    {
        // The socket API takes whole milliseconds and reads 0 as no timeout at all
        assertThat(connectErrorsFor(Duration.ofNanos(999_999))).singleElement().asString().contains("connect");
        assertThat(connectErrorsFor(Duration.ZERO)).singleElement().asString().contains("connect");
    }

    @Test
    void aReadTimeoutBelowOneMillisecondIsRefused()
    {
        assertThat(errorsFor(Duration.ofNanos(500_000))).singleElement().asString().contains("read");
    }

    @Test
    void aConnectTimeoutOfOneMillisecondIsAccepted()
    {
        assertThat(connectErrorsFor(Duration.ofMillis(1))).isEmpty();
    }

    @Test
    void theDefaultsApplyWhenNothingIsSet()
    {
        assertThat(TimeoutConfig.defaults().read()).isEqualTo(Duration.ofSeconds(30));
        assertThat(TimeoutConfig.defaults().connect()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void aRouteReadsItsConnectTimeout() throws IOException
    {
        final Path file = this.dir.resolve("routes.yaml");
        Files.writeString(file, """
                version: test
                routes:
                  - id: quick
                    match:
                      - PathPrefix:
                          prefix: /quick
                    upstream:
                      targets:
                        - url: http://localhost:1
                      timeouts:
                        connect: 250ms
                """);
        final RoutesDefinition definition = ConfigurationManager.load(file, RoutesDefinition.class);

        assertThat(definition.routes().getFirst().upstream().timeouts().connect()).isEqualTo(Duration.ofMillis(250));
    }

    private static List<String> connectErrorsFor(final Duration connect)
    {
        final ValidationResult result = new ValidationResult();
        new TimeoutConfig(null, connect).validate(result);
        return result.getErrors();
    }

    private static List<String> errorsFor(final Duration read)
    {
        final ValidationResult result = new ValidationResult();
        new TimeoutConfig(read, null).validate(result);
        return result.getErrors();
    }
}
