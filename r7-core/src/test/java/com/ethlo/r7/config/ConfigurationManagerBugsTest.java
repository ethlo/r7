package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.util.TestFingerprints;

/**
 * Reproduces bugs found in an earlier review of the config validation path. Each test fails
 * against the code as it stood before the fix, and documents the fix's contract afterward.
 */
class ConfigurationManagerBugsTest
{
    @TempDir
    Path dir;

    private final ConfigurationManager manager = new ConfigurationManager(TestFingerprints.engine());

    /**
     * M1: an unknown predicate under 'not:' used to be silently poison-pilled to
     * FalsePredicate, which 'not' then inverted to TruePredicate - the route matched every
     * request instead of failing to load.
     */
    @Test
    void unknownPredicateUnderNotFailsValidationInsteadOfMatchingEverything() throws Exception
    {
        final Path file = write("""
                version: test
                routes:
                  - id: bad
                    match:
                      - not:
                          NoSuchPredicate:
                            foo: bar
                    upstream:
                      targets:
                        - url: http://localhost:1
                """);

        final RoutesDefinition config = ConfigurationManager.load(file, RoutesDefinition.class);
        assertThatThrownBy(() -> manager.build(config))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("NoSuchPredicate");
    }

    /**
     * M2: a Spring-Cloud-Gateway shorthand entry ('- Path=/admin/**') is a bare YAML scalar, not
     * a map; ConditionDefinition.create silently produced an empty (always-true) node for it.
     */
    @Test
    void scalarShorthandMatchEntryIsRejectedWithTheCorrectForm() throws Exception
    {
        final Path file = write("""
                version: test
                routes:
                  - id: bad
                    match:
                      - Path=/admin/**
                    upstream:
                      targets:
                        - url: http://localhost:1
                """);

        assertThatThrownBy(() -> ConfigurationManager.load(file, RoutesDefinition.class))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Path=/admin/**")
                .hasMessageContaining("Path:");
    }

    private Path write(final String yaml) throws Exception
    {
        final Path file = dir.resolve("routes.yaml");
        Files.writeString(file, yaml);
        return file;
    }
}
