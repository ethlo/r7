package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ethlo.r7.spi.EngineContext;
import com.ethlo.r7.util.PredicateRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * A value given to an exact-match predicate that looks like a regex loads fine and never
 * matches; the check warns about it, at the value's path and position, and stays quiet on
 * ordinary literal values.
 */
class RegexLookalikeCheckTest
{
    private static final ObjectMapper MAPPER = YamlConfigSupport.baseMapperBuilder().build();

    @TempDir
    Path dir;

    @Test
    void warnsAtThePathAndPositionOfTheValue()
    {
        final List<String> warnings = check("""
                routes:
                  - id: api
                    match:
                      - Path:
                          path: /api/.*
                """);

        assertThat(warnings).containsExactly(
                "[routes[0].match[0].Path.path] line 5, column 17: Path compares this value exactly, but it looks like a regular expression ('.*'). "
                        + "Use PathPrefix to match everything under a path, or MatchPath with a regular expression.");
    }

    @Test
    void findsValuesInsideLogicalOperators()
    {
        final List<String> warnings = check("""
                routes:
                  - id: plain
                    match:
                      - Path:
                          path: /health
                  - id: nested
                    match:
                      - and:
                          - PathPrefix:
                              prefix: /api/
                          - or:
                              - RequestHeader:
                                  name: Authorization
                                  value: ^Bearer .+$
                              - not:
                                  QueryParameter:
                                    name: mode
                                    value: (debug|trace)
                          - Cookie:
                              name: session
                              value: "[a-f0-9]{32}"
                """);

        assertThat(warnings).hasSize(3);
        assertThat(warnings.get(0)).startsWith("[routes[1].match[0].and[1].or[0].RequestHeader.value] line 14, column 26: RequestHeader")
                .contains("a regular expression ('^')", "Use MatchRequestHeader");
        assertThat(warnings.get(1)).startsWith("[routes[1].match[0].and[1].or[1].not.QueryParameter.value] line 18, column 28: QueryParameter")
                .contains("('(debug|trace)')", "Use MatchQueryParameter");
        assertThat(warnings.get(2)).startsWith("[routes[1].match[0].and[2].Cookie.value] line 21, column 22: Cookie")
                .contains("Use MatchCookie");
    }

    @Test
    void warnsOnSpringCloudGatewayWildcardsInPathsAndHosts()
    {
        final List<String> warnings = check("""
                routes:
                  - id: a
                    match:
                      - Path:
                          path: /api/**
                  - id: b
                    match:
                      - Path:
                          path: /users/{id}
                  - id: c
                    match:
                      - Host:
                          hosts:
                            - example.com
                            - "*.example.com"
                """);

        assertThat(warnings).hasSize(3);
        assertThat(warnings.get(0)).startsWith("[routes[0].match[0].Path.path]").contains("a wildcard pattern ('**')");
        assertThat(warnings.get(1)).startsWith("[routes[1].match[0].Path.path]").contains("a template ('{id}')");
        assertThat(warnings.get(2)).startsWith("[routes[2].match[0].Host.hosts[1]] line 15, column 15").contains("a wildcard pattern ('*')", "MatchRequestHeader");
    }

    @Test
    void ignoresRegexPredicatesAndOtherFields()
    {
        assertThat(check("""
                routes:
                  - id: a
                    match:
                      - MatchPath:
                          regexp: /api/.*
                      - MatchRequestHeader:
                          name: X-Id
                          regexp: ^\\d+$
                      - Method:
                          methods: [GET]
                """)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/users", "/files/report.pdf", "/a+b", "/what?", "text/html; q=0.9", "*/*",
            "Bearer abc.def.ghi", "eyJhbGciOi.eyJzdWIiOi.sig-_x", "dGVzdA+/ab==", "1.5", "US$5", "[draft]", "{}", "a|b"
    })
    void literalValuesAreNotSuspicious(final String value)
    {
        assertThat(RegexLookalikeCheck.suspicion(value, false)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/.*", "/api/.+", "^/api", "/api$", "[a-z]+", "[^/]+", "\\d+", "v\\.1", "(a|b)", "(?:x|y)", "(?i)admin", "\\w{3}", "[0-9]{4}"
    })
    void regexConstructsAreSuspicious(final String value)
    {
        assertThat(RegexLookalikeCheck.suspicion(value, false)).startsWith("a regular expression");
    }

    @Test
    void wildcardsCountOnlyWhereTheFieldSaysSo()
    {
        assertThat(RegexLookalikeCheck.suspicion("*/*", false)).isNull();
        assertThat(RegexLookalikeCheck.suspicion("/static/*.css", true)).isEqualTo("a wildcard pattern ('*')");
        assertThat(RegexLookalikeCheck.suspicion("{tenant}.example.com", true)).isEqualTo("a template ('{tenant}')");
    }

    @Test
    void checksTheInterpolatedValueAndStillLoads() throws Exception
    {
        final Path file = dir.resolve("routes.yaml");
        Files.writeString(file, """
                routes:
                  - id: a
                    match:
                      - Path:
                          path: ${R7_TEST_UNSET_PATH:/api/.*}
                    upstream:
                      targets:
                        - url: http://localhost:1
                """);

        final ConfigurationManager manager = new ConfigurationManager(new EngineContext(Map.of()));
        final RoutesDefinition definition = manager.loadRoutes(file);

        assertThat(manager.build(definition).routes()).hasSize(1);
        assertThat(YamlConfigSupport.load(MAPPER, file, RoutesDefinition.class, (root, positions) ->
                assertThat(new RegexLookalikeCheck(new PredicateRegistry(MAPPER)).check(root, positions))
                        .singleElement().asString().contains("('.*')"))).isNotNull();
    }

    private List<String> check(final String yaml)
    {
        final RegexLookalikeCheck check = new RegexLookalikeCheck(new PredicateRegistry(MAPPER));
        return check.check(MAPPER.readTree(yaml), new YamlSourcePositions(MAPPER, yaml));
    }
}
