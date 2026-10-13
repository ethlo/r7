package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.spi.EngineContext;

/**
 * Reproduces bugs found in an earlier review of the config validation path. Each test fails
 * against the code as it stood before the fix, and documents the fix's contract afterward.
 */
class ConfigurationManagerBugsTest
{
    @TempDir
    Path dir;

    private final ConfigurationManager manager = new ConfigurationManager(new EngineContext(Map.of()));

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

    /**
     * An empty match block is documented as never matching (fallback-only routes), but
     * build() turned the empty tree into TruePredicate: the route caught every request.
     */
    @Test
    void emptyMatchNeverMatches() throws Exception
    {
        final Path file = write("""
                version: test
                routes:
                  - id: fallback-only
                    match: []
                    upstream:
                      targets:
                        - url: http://localhost:1
                """);

        final RoutesDefinition config = ConfigurationManager.load(file, RoutesDefinition.class);
        assertThat(manager.build(config).routes().getFirst().predicate()).isSameAs(FalsePredicate.INSTANCE);
    }

    /**
     * An empty node below the top built to true as well: 'not: {}' and '- {}' made the route a
     * catch-all, and 'or: []' dropped out of the tree. They are refused instead.
     */
    @Test
    void emptyNestedConditionsAreRefused() throws Exception
    {
        // Only a top-level '[]' is fallback-only: '{ or: [] }' and '{ and: [] }' at the top used
        // to pass as empty too, and silently turned the route into a fallback-only one.
        for (final String match : new String[]{"[ {} ]", "[ { not: {} } ]", "[ { or: [] } ]", "[ { Method: { methods: [GET] }, and: [] } ]",
                "{}", "{ and: [] }", "{ or: [] }", "{ not: {} }", "[ [] ]"})
        {
            final Path file = write("""
                    version: test
                    routes:
                      - id: bad
                        match: %s
                        upstream:
                          targets:
                            - url: http://localhost:1
                    """.formatted(match));

            final RoutesDefinition config = ConfigurationManager.load(file, RoutesDefinition.class);
            assertThatThrownBy(() -> manager.build(config))
                    .as(match)
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("Empty");
        }
    }

    /**
     * 'and'/'or' written as a mapping became an empty list: the route loaded and never matched.
     */
    @Test
    void logicalOperatorWrittenAsAMappingIsRefused() throws Exception
    {
        for (final String match : new String[]{"{ and: { Method: { methods: [GET] } } }", "[ { or: { Method: { methods: [GET] } } } ]"})
        {
            final Path file = write("""
                    version: test
                    routes:
                      - id: bad
                        match: %s
                        upstream:
                          targets:
                            - url: http://localhost:1
                    """.formatted(match));

            assertThatThrownBy(() -> manager.build(ConfigurationManager.load(file, RoutesDefinition.class)))
                    .as(match)
                    .hasMessageContaining("takes a list of conditions")
                    .hasMessageMatching("(?s).*\\[routes\\.bad\\.match(\\[0])?\\.(and|or)].*");
        }
    }

    /**
     * 'not: [A, B]' was ANDed, NOT(A AND B), and reads just as well as NOT(A OR B).
     */
    @Test
    void notWrittenAsAListIsRefused() throws Exception
    {
        final Path file = write("""
                version: test
                routes:
                  - id: bad
                    match:
                      - not:
                          - Method: { methods: [GET] }
                    upstream:
                      targets:
                        - url: http://localhost:1
                """);

        assertThatThrownBy(() -> manager.build(ConfigurationManager.load(file, RoutesDefinition.class)))
                .hasMessageContaining("'not' takes one condition")
                .hasMessageContaining("[routes.bad.match[0].not]");
    }

    /**
     * A route with filters and no match loaded and never matched; fallback-only is 'match: []'.
     */
    @Test
    void routeWithoutMatchIsRefused() throws Exception
    {
        final Path file = write("""
                version: test
                routes:
                  - id: no-match
                    filters:
                      - ReturnResponse: { status: 200 }
                """);

        assertThatThrownBy(() -> manager.build(ConfigurationManager.load(file, RoutesDefinition.class)))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("match: []");
    }

    private Path write(final String yaml) throws Exception
    {
        final Path file = dir.resolve("routes.yaml");
        Files.writeString(file, yaml);
        return file;
    }
}
