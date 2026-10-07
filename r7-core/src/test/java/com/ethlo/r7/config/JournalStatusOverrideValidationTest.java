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
import org.junit.jupiter.params.provider.ValueSource;

import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.util.TestFingerprints;

class JournalStatusOverrideValidationTest
{
    @TempDir
    Path dir;

    private RouteRegistry load(final String direction, final String key) throws IOException
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
                        level: METADATA
                        status_overrides:
                          "%s": HEADERS
                """.formatted(direction, key));
        final RoutesDefinition definition = ConfigurationManager.load(file, RoutesDefinition.class);
        final RouteRegistry registry = new RouteRegistry();
        new ConfigurationManager(TestFingerprints.engine()).load(definition, registry);
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
}
