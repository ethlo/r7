package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.spi.EngineContext;

class JournalStatusOverrideValidationTest
{
    @TempDir
    Path dir;

    private RouteRegistry load(final String direction, final String key) throws IOException
    {
        return load(direction, JournalLevel.METADATA, key, JournalLevel.HEADERS);
    }

    private RouteRegistry load(final String direction, final JournalLevel base, final String key, final JournalLevel override) throws IOException
    {
        final Path file = this.dir.resolve("routes.yaml");
        Files.writeString(file, """
                version: test
                routes:
                  - id: my-route
                    match:
                      - PathPrefix:
                          prefix: /
                    upstream:
                      targets:
                        - url: http://localhost:1
                    journal:
                      %s:
                        level: %s
                        status_overrides:
                          "%s": %s
                """.formatted(direction, base, key, override));
        final RoutesDefinition definition = ConfigurationManager.load(file, RoutesDefinition.class);
        final RouteRegistry registry = new RouteRegistry();
        new ConfigurationManager(new EngineContext(Map.of())).load(definition, registry);
        return registry;
    }

    @ParameterizedTest
    @ValueSource(strings = {"500-599", "5XX-ish", "999", "99", "600", "0xx", "6xx", "x", "", "401,", "401,,403", "401,abc", "401,999", "+429", "4290"})
    void rejectsInvalidKeyNamingTheFieldAndTheAcceptedForms(final String key)
    {
        assertThatThrownBy(() -> load("response", key))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("[routes.my-route.journal.response.status_overrides]")
                .hasMessageContaining("'" + key + "'")
                .hasMessageContaining("Nxx")
                .hasMessageContaining("100-599");
    }

    @Test
    void namesTheRequestDirection()
    {
        assertThatThrownBy(() -> load("request", "500-599"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("[routes.my-route.journal.request.status_overrides]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"5xx", "5XX", "1xx", " 4xx ", "429", "100", "599", "401,403", "401, 403 ,404"})
    void acceptsValidKeys(final String key)
    {
        assertThatCode(() -> load("response", key)).doesNotThrowAnyException();
    }

    @Test
    void expandsKeysToTheCodesTheyCover()
    {
        final JournalLevel[] expanded = JournalOverrideParser.parseOverrides(Map.of(
                "4xx", JournalLevel.HEADERS,
                "500,503", JournalLevel.FULL,
                "599", JournalLevel.NONE
        ));
        assertThat(expanded[399]).isNull();
        assertThat(expanded[400]).isEqualTo(JournalLevel.HEADERS);
        assertThat(expanded[499]).isEqualTo(JournalLevel.HEADERS);
        assertThat(expanded[500]).isEqualTo(JournalLevel.FULL);
        assertThat(expanded[501]).isNull();
        assertThat(expanded[503]).isEqualTo(JournalLevel.FULL);
        assertThat(expanded[599]).isEqualTo(JournalLevel.NONE);
    }

    @Test
    void appliesAnOverrideForTheHighestStatusCode()
    {
        final JournalDirectionConfig config = new JournalDirectionConfig(JournalLevel.METADATA,
                JournalOverrideParser.parseOverrides(Map.of("599", JournalLevel.HEADERS)));
        assertThat(config.resolve(599)).isEqualTo(JournalLevel.HEADERS);
        assertThat(config.resolve(598)).isEqualTo(JournalLevel.METADATA);
    }

    @ParameterizedTest
    @ValueSource(strings = {"request", "response"})
    void rejectsElevationToFullInEitherDirectionNamingTheDirection(final String direction)
    {
        for (final JournalLevel base : new JournalLevel[]{JournalLevel.NONE, JournalLevel.METADATA, JournalLevel.HEADERS})
        {
            assertThatThrownBy(() -> load(direction, base, "5xx", JournalLevel.FULL))
                    .as("base %s", base)
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("[routes.my-route.journal." + direction + ".status_overrides]")
                    .hasMessageContaining("Cannot elevate status override '5xx' to FULL when the base level is " + base);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"request", "response"})
    void acceptsFullOverrideWhenTheBaseIsFull(final String direction)
    {
        assertThatCode(() -> load(direction, JournalLevel.FULL, "5xx", JournalLevel.FULL)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = JournalLevel.class, names = {"NONE", "METADATA", "HEADERS"})
    void rejectsLoweringARequestOverrideBelowFullWhenTheBaseIsFull(final JournalLevel override)
    {
        assertThatThrownBy(() -> load("request", JournalLevel.FULL, "404", override))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("[routes.my-route.journal.request.status_overrides]")
                .hasMessageContaining("Cannot lower request status override '404' to " + override)
                .hasMessageNotContaining("journal.response");
    }

    @ParameterizedTest
    @EnumSource(value = JournalLevel.class, names = {"NONE", "METADATA", "HEADERS"})
    void acceptsLoweringAResponseOverrideBelowFullWhenTheBaseIsFull(final JournalLevel override)
    {
        // The response status is known before the first response body byte is journaled,
        // so a downgrade on this side is honoured, unlike on the request side.
        assertThatCode(() -> load("response", JournalLevel.FULL, "404", override)).doesNotThrowAnyException();
    }

    @Test
    void reportsAnInvalidKeyOnceRatherThanAlsoAsADowngrade()
    {
        assertThatThrownBy(() -> load("request", JournalLevel.FULL, "500-599", JournalLevel.METADATA))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Invalid status override key '500-599'")
                .hasMessageNotContaining("Cannot lower");
    }
}
