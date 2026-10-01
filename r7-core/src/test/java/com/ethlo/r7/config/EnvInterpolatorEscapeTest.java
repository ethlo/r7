package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code ${...}} is also TemplateRedirect's syntax for a named capture group. Interpolation runs
 * over every scalar first, so a target of {@code /new/${rest}} used to fail validation with a
 * "missing environment variable 'rest'" - or, if a variable of that name existed, redirect to its
 * value. {@code $${...}} is the escape that leaves the literal text for the filter.
 */
class EnvInterpolatorEscapeTest
{
    @TempDir
    Path dir;

    @AfterEach
    void cleanup()
    {
        System.clearProperty("rest");
        System.clearProperty("R7_TEST_HOST");
    }

    @Test
    void anEscapedReferenceIsLeftAsLiteralText()
    {
        assertThat(EnvInterpolator.interpolate("/new/$${rest}")).isEqualTo("/new/${rest}");
        assertThat(EnvInterpolator.interpolate("$${a:b}")).isEqualTo("${a:b}");
    }

    @Test
    void anEscapedReferenceIgnoresAVariableOfTheSameName()
    {
        System.setProperty("rest", "https://evil.example");
        assertThat(EnvInterpolator.interpolate("/new/$${rest}")).isEqualTo("/new/${rest}");
        assertThat(EnvInterpolator.interpolate("/new/${rest}")).isEqualTo("/new/https://evil.example");
    }

    @Test
    void escapesAndVariablesMixInOneValue()
    {
        System.setProperty("R7_TEST_HOST", "good.example");
        assertThat(EnvInterpolator.interpolate("https://${R7_TEST_HOST}/$${rest}?a=$1"))
                .isEqualTo("https://good.example/${rest}?a=$1");
    }

    @Test
    void anUnescapedReferenceIsStillAVariable()
    {
        assertThatThrownBy(() -> EnvInterpolator.interpolate("/new/${rest}"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("'rest'");
    }

    @Test
    void theEscapeSurvivesLoadingAConfigFile() throws Exception
    {
        final Path file = dir.resolve("routes.yaml");
        Files.writeString(file, """
                version: "$${rest}"
                routes:
                  - id: only
                    match:
                      - Path:
                          path: /ok
                    upstream:
                      targets:
                        - url: http://localhost:1
                """);

        assertThat(ConfigurationManager.load(file, RoutesDefinition.class).version()).isEqualTo("${rest}");
    }
}
