package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * L4: interpolation used to run with a single regex pass over the raw YAML text, before it was
 * parsed. An environment value that happens to contain YAML metacharacters (a colon, a newline)
 * was then free to add or change YAML nodes the author never wrote - not just fill in the scalar
 * it was substituted into. Interpolating each scalar's own text, after parsing, confines a
 * substituted value to being text.
 */
class EnvInterpolationBugTest
{
    @TempDir
    Path dir;

    @AfterEach
    void cleanup()
    {
        System.clearProperty("R7_TEST_INJECT");
    }

    @Test
    void anEnvironmentValueCannotInjectAdditionalYamlStructure() throws Exception
    {
        // A value containing a newline followed by a YAML key: 'unrouted' - if substituted into
        // the raw document text before parsing, this stops being part of the 'version' scalar and
        // becomes a sibling mapping entry instead.
        System.setProperty("R7_TEST_INJECT", "v1\nunrouted:\n  journal:\n    request:\n      level: FULL");

        final Path file = dir.resolve("routes.yaml");
        Files.writeString(file, """
                version: ${R7_TEST_INJECT}
                routes:
                  - id: only
                    match:
                      - Path:
                          path: /ok
                    upstream:
                      targets:
                        - url: http://localhost:1
                """);

        final RoutesDefinition config = ConfigurationManager.load(file, RoutesDefinition.class);

        // The whole injected string lands verbatim in 'version' ...
        assertThat(config.version()).isEqualTo("v1\nunrouted:\n  journal:\n    request:\n      level: FULL");
        // ... and does not also stand up an 'unrouted' policy that was never written in the file.
        assertThat(config.unrouted()).isNull();
        assertThat(config.routes()).hasSize(1);
    }
}
