package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.condition.Condition;
import com.ethlo.r7.condition.ConditionInput;
import com.ethlo.r7.condition.Leg;
import com.ethlo.r7.condition.RequestCondition;
import com.ethlo.r7.spi.EngineContext;

/**
 * The three declaration levels of routes.yaml: the root, a group and a route. Each level's
 * filters are one instance for everything beneath it, upstream keys are inherited key by key,
 * and a group's match is tested once before its routes'.
 */
class ConfigLevelsTest
{
    @TempDir
    Path dir;

    private RouteRegistry.Snapshot build(final String yaml) throws IOException
    {
        final Path file = this.dir.resolve("routes.yaml");
        Files.writeString(file, yaml);
        return new ConfigurationManager(new EngineContext(Map.of())).buildFrom(file);
    }

    private static DefaultGatewayRoute route(final RouteRegistry.Snapshot snapshot, final String id)
    {
        return (DefaultGatewayRoute) snapshot.routes().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    private static ConditionInput request(final String path)
    {
        final GatewayRequest request = mock(GatewayRequest.class);
        when(request.path()).thenReturn(path);
        when(request.uri()).thenReturn(path);
        when(request.method()).thenReturn("GET");
        return ConditionInput.of(request);
    }

    private static Condition predicate(final java.util.function.Predicate<GatewayRequest> test)
    {
        final GatewayPredicate predicate = mock(GatewayPredicate.class);
        when(predicate.test(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> test.test(i.getArgument(0)));
        return new RequestCondition(predicate, Leg.CLIENT);
    }

    private static final String GROUPED = """
            version: levels
            filters:
              - RateLimiter:
                  capacity: 100
                  refill_tokens: 100
                  refill_period: 1s
            upstream:
              preserve_host: true
              timeouts:
                read: 7s
              targets:
                - url: http://root:1
            routes:
              - group: api
                match:
                  - PathPrefix:
                      prefix: /api
                filters:
                  - RateLimiter:
                      capacity: 10
                      refill_tokens: 10
                      refill_period: 1s
                upstream:
                  targets:
                    - url: http://api:1
                routes:
                  - id: users
                    match:
                      - PathPrefix:
                          prefix: /api/users
                  - id: orders
                    match:
                      - PathPrefix:
                          prefix: /api/orders
                    filters:
                      - AddRequestHeader:
                          name: X-Orders
                          value: "1"
                    upstream:
                      preserve_host: false
                      timeouts:
                        read: 2s
              - id: catch-all
                match:
                  - PathPrefix:
                      prefix: /
            """;

    @Test
    void groupsAreFlattenedInOrderAndRoutingFallsThroughAGroupNoRouteOfMatches() throws IOException
    {
        final RouteRegistry.Snapshot snapshot = build(GROUPED);

        assertThat(snapshot.routes()).extracting(GatewayRoute::id).containsExactly("users", "orders", "catch-all");
        assertThat(snapshot.findRoute(request("/api/users/1")).id()).isEqualTo("users");
        assertThat(snapshot.findRoute(request("/api/orders")).id()).isEqualTo("orders");
        // The group matches, none of its routes does: on to the next entry
        assertThat(snapshot.findRoute(request("/api/other")).id()).isEqualTo("catch-all");
        assertThat(snapshot.findRoute(request("/elsewhere")).id()).isEqualTo("catch-all");
        assertThat(route(snapshot, "users").group()).isEqualTo("api");
        assertThat(route(snapshot, "catch-all").group()).isNull();
    }

    @Test
    void aGroupsPredicateIsTestedOnceForAllItsRoutes()
    {
        final AtomicInteger groupTests = new AtomicInteger();
        final Condition group = predicate(r -> groupTests.incrementAndGet() > 0);
        final Condition never = predicate(r -> false);
        final GatewayRoute a = mock(GatewayRoute.class);
        final GatewayRoute b = mock(GatewayRoute.class);
        final RouteRegistry.Snapshot snapshot = new RouteRegistry.Snapshot("v", null, List.of(a, b),
                List.of(new RouteRegistry.Group("g", group, List.of(a, b), List.of(never, never))), null);

        assertThat(snapshot.findRoute(request("/x"))).isNull();
        assertThat(groupTests).hasValue(1);
    }

    @Test
    void upstreamKeysAreInheritedOneByOne() throws IOException
    {
        final RouteRegistry.Snapshot snapshot = build(GROUPED);

        final UpstreamConfig users = route(snapshot, "users").routeDefinition().upstream();
        assertThat(users.targets()).extracting(t -> t.url().toString()).containsExactly("http://api:1");
        assertThat(users.timeouts().read()).hasSeconds(7);
        assertThat(users.preserveHost()).isTrue();

        final UpstreamConfig orders = route(snapshot, "orders").routeDefinition().upstream();
        assertThat(orders.targets()).extracting(t -> t.url().toString()).containsExactly("http://api:1");
        assertThat(orders.timeouts().read()).hasSeconds(2);
        assertThat(orders.preserveHost()).isFalse();

        final UpstreamConfig catchAll = route(snapshot, "catch-all").routeDefinition().upstream();
        assertThat(catchAll.targets()).extracting(t -> t.url().toString()).containsExactly("http://root:1");
    }

    @Test
    void anUpstreamBelongsToTheInnermostLevelNamingAnyOfItsKeys() throws IOException
    {
        final RouteRegistry.Snapshot snapshot = build(GROUPED + """
                  - group: plain
                    upstream: {}
                    routes:
                      - id: plain-a
                        match:
                          - PathPrefix:
                              prefix: /plain/a
                      - id: plain-b
                        match:
                          - PathPrefix:
                              prefix: /plain/b
                        upstream: {}
                """);

        assertThat(route(snapshot, "users").upstreamKey()).isEqualTo("group:api");
        assertThat(route(snapshot, "users").upstreamLevel()).isEqualTo(DefaultGatewayRoute.Level.GROUP);
        assertThat(route(snapshot, "orders").upstreamKey()).isEqualTo("route:orders");
        // Neither the route nor its group names a key, empty blocks included: the root's
        assertThat(route(snapshot, "catch-all").upstreamKey()).isEqualTo("root");
        assertThat(route(snapshot, "plain-a").upstreamKey()).isEqualTo("root");
        assertThat(route(snapshot, "plain-b").upstreamKey()).isEqualTo("root");
    }

    @Test
    void eachLevelsFiltersAreOneInstanceForEverythingBeneathIt() throws IOException
    {
        final RouteRegistry.Snapshot snapshot = build(GROUPED);
        final DefaultGatewayRoute users = route(snapshot, "users");
        final DefaultGatewayRoute orders = route(snapshot, "orders");
        final DefaultGatewayRoute catchAll = route(snapshot, "catch-all");

        // Root first, then the group's, then the route's own
        assertThat(users.filters()).hasSize(2);
        assertThat(orders.filters()).hasSize(3);
        assertThat(catchAll.filters()).hasSize(1);

        assertThat(users.filters().get(0)).isSameAs(orders.filters().get(0)).isSameAs(catchAll.filters().get(0));
        assertThat(users.filters().get(1)).isSameAs(orders.filters().get(1)).isNotSameAs(users.filters().get(0));

        assertThat(orders.filterLevel(0)).isEqualTo(DefaultGatewayRoute.Level.ROOT);
        assertThat(orders.filterLevel(1)).isEqualTo(DefaultGatewayRoute.Level.GROUP);
        assertThat(orders.filterLevel(2)).isEqualTo(DefaultGatewayRoute.Level.ROUTE);
    }

    @Test
    void aRouteWithNoTargetsAtAnyLevelHasNoUpstreamUnlessItOrItsGroupNamesUpstreamKeys() throws IOException
    {
        final RouteRegistry.Snapshot snapshot = build("""
                upstream:
                  timeouts:
                    read: 7s
                routes:
                  - id: static
                    match:
                      - PathPrefix:
                          prefix: /
                    filters:
                      - ReturnResponse:
                          status: 200
                          body: ok
                """);
        assertThat(route(snapshot, "static").routeDefinition().upstream()).isNull();

        // An empty block names no key: the same as leaving it out
        final RouteRegistry.Snapshot empty = build("""
                routes:
                  - group: g
                    upstream: {}
                    routes:
                      - id: static
                        match:
                          - PathPrefix:
                              prefix: /
                        upstream: {}
                        filters:
                          - ReturnResponse:
                              status: 200
                              body: ok
                """);
        assertThat(route(empty, "static").routeDefinition().upstream()).isNull();

        assertThatThrownBy(() -> build("""
                routes:
                  - group: g
                    upstream:
                      timeouts:
                        read: 2s
                    routes:
                      - id: a
                        match:
                          - PathPrefix:
                              prefix: /
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("routes.g.routes.a.upstream.targets")
                .hasMessageContaining("name targets here, on group 'g' or at the root");
    }

    @Test
    void groupAndRouteIdsShareOneNamespace()
    {
        assertThatThrownBy(() -> build("""
                routes:
                  - group: a
                    routes:
                      - id: b
                        match:
                          - PathPrefix:
                              prefix: /b
                        upstream:
                          targets:
                            - url: http://localhost:1
                  - id: a
                    match:
                      - PathPrefix:
                          prefix: /a
                    upstream:
                      targets:
                        - url: http://localhost:1
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("id is not unique");
    }

    @Test
    void globalFiltersIsAnUnknownOption()
    {
        assertThatThrownBy(() -> build("""
                global_filters:
                  - SimpleMetrics
                routes:
                  - id: a
                    match:
                      - PathPrefix:
                          prefix: /
                    upstream:
                      targets:
                        - url: http://localhost:1
                """))
                .hasMessageContaining("Unknown configuration option: 'global_filters'");
    }

    @Test
    void entryShapesAreRefusedByName()
    {
        assertThatThrownBy(() -> build("""
                routes:
                  - id: a
                    group: a
                    routes: []
                """)).hasMessageContaining("has both 'id' and 'group'");

        assertThatThrownBy(() -> build("""
                routes:
                  - match:
                      - PathPrefix:
                          prefix: /
                """)).hasMessageContaining("needs 'id' for a route, or 'group' for a group of routes");

        assertThatThrownBy(() -> build("""
                routes:
                  - group: outer
                    routes:
                      - group: inner
                        routes: []
                """)).hasMessageContaining("is a group inside a group");

        assertThatThrownBy(() -> build("""
                routes:
                  - id: a
                    match: []
                    routes: []
                """)).hasMessageContaining("write 'group: a' instead of 'id: a'");
    }

    @Test
    void anExplicitNullCannotRemoveAnInheritedValue()
    {
        assertThatThrownBy(() -> build("""
                upstream:
                  targets:
                    - url: http://localhost:1
                routes:
                  - id: a
                    match:
                      - PathPrefix:
                          prefix: /
                    upstream:
                      targets: null
                """)).hasMessageContaining("null cannot remove an inherited targets; leave the key out to inherit it.");

        assertThatThrownBy(() -> build("""
                routes:
                  - group: g
                    filters: null
                    routes:
                      - id: a
                        match:
                          - PathPrefix:
                              prefix: /
                """)).hasMessageContaining("null cannot remove inherited filters; leave the key out.");
    }

    @Test
    void aFallbackInheritedFromAGroupMayNotNameTheRouteItself()
    {
        assertThatThrownBy(() -> build("""
                routes:
                  - group: g
                    upstream:
                      targets:
                        - url: http://localhost:1
                      fallback:
                        route_id: spare
                    routes:
                      - id: a
                        match:
                          - PathPrefix:
                              prefix: /a
                      - id: spare
                        match:
                          - PathPrefix:
                              prefix: /spare
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Route 'spare' inherits fallback.route_id 'spare' from group 'g', which names the route itself");
    }

    @Test
    void aRootFallbackNamesEveryRouteIncludingTheFallbackItself()
    {
        assertThatThrownBy(() -> build("""
                upstream:
                  targets:
                    - url: http://localhost:1
                  fallback:
                    route_id: spare
                routes:
                  - id: a
                    match:
                      - PathPrefix:
                          prefix: /a
                  - id: spare
                    match:
                      - PathPrefix:
                          prefix: /spare
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Route 'spare' inherits fallback.route_id 'spare' from the root");
    }

    @Test
    void aFallbackNamesARouteNotAGroup()
    {
        assertThatThrownBy(() -> build("""
                routes:
                  - id: a
                    match:
                      - PathPrefix:
                          prefix: /a
                    upstream:
                      targets:
                        - url: http://localhost:1
                      fallback:
                        route_id: g
                  - group: g
                    upstream:
                      targets:
                        - url: http://localhost:1
                    routes:
                      - id: b
                        match:
                          - PathPrefix:
                              prefix: /b
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("'g' that is a group");
    }

    /**
     * A fallback into another group: the root's and the first group's request filters already
     * ran and are not run again; the fallback group's filters run, as for a request that matched
     * there; the first route's upstream-only filters shaped a request for another upstream and do
     * not run at all.
     */
    @Test
    void aFallbackIntoAnotherGroupRunsThatGroupsFiltersOnly() throws IOException
    {
        final RouteRegistry.Snapshot snapshot = build("""
                filters:
                  - RateLimiter:
                      capacity: 100
                      refill_tokens: 100
                      refill_period: 1s
                routes:
                  - group: primary
                    filters:
                      - RateLimiter:
                          capacity: 10
                          refill_tokens: 10
                          refill_period: 1s
                      - AddRequestHeader:
                          name: X-Primary
                          value: "1"
                    upstream:
                      targets:
                        - url: http://primary:1
                      fallback:
                        route_id: spare
                    routes:
                      - id: a
                        match:
                          - PathPrefix:
                              prefix: /a
                  - group: secondary
                    filters:
                      - RateLimiter:
                          capacity: 5
                          refill_tokens: 5
                          refill_period: 1s
                    upstream:
                      targets:
                        - url: http://secondary:1
                    routes:
                      - id: spare
                        match:
                          - PathPrefix:
                              prefix: /spare
                """);
        final DefaultGatewayRoute a = route(snapshot, "a");
        final DefaultGatewayRoute spare = route(snapshot, "spare");

        final DefaultGatewayRoute spareAfterA = a.asFallbackOfThis(spare);

        final ClientRequestGatewayFilter rootLimiter = a.clientRequestFilters()[0];
        final ClientRequestGatewayFilter primaryLimiter = a.clientRequestFilters()[1];
        final ClientRequestGatewayFilter secondaryLimiter = spare.clientRequestFilters()[1];
        assertThat(spareAfterA.clientRequestFilters()).containsExactly(rootLimiter, primaryLimiter, secondaryLimiter);
        assertThat(spareAfterA.startedClientRequestFilterCount()).isEqualTo(2);
        assertThat(spareAfterA.beforeUpstreamGatewayFilters()).isEmpty();
        assertThat(spareAfterA.group()).isEqualTo("secondary");
    }

    @Test
    void theSnapshotCarriesTheDigestOfTheFileAsRead() throws IOException, NoSuchAlgorithmException
    {
        final String yaml = """
                version: digest
                routes:
                  - id: a
                    match:
                      - PathPrefix:
                          prefix: /
                    upstream:
                      targets:
                        - url: ${R7_TEST_UNSET_URL:http://localhost:1}
                """;
        final RouteRegistry.Snapshot snapshot = build(yaml);

        // Before interpolation: the file, not what the environment made of it
        final String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(yaml.getBytes(StandardCharsets.UTF_8)));
        assertThat(snapshot.sourceDigest()).isEqualTo(expected);
        assertThat(snapshot.version()).isEqualTo("digest");
    }

    /**
     * A RewritePath that sets the query must be the only RewritePath a route runs, counting the
     * root's and the group's; rewrites that only change the path may be combined.
     */
    @Test
    void aQuerySettingRewriteMustBeTheRoutesOnlyRewrite() throws IOException
    {
        assertThatThrownBy(() -> build("""
                filters:
                  - RewritePath:
                      regexp: "^/a/(.*)$"
                      replacement: "/$1"
                routes:
                  - group: g
                    routes:
                      - id: r
                        match:
                          - PathPrefix:
                              prefix: /a
                        filters:
                          - RewritePath:
                              regexp: "^/a/x$"
                              replacement: "/y?z=1"
                        upstream:
                          targets:
                            - url: http://localhost:1
                """))
                .hasMessageContaining("routes.g.routes.r.filters")
                .hasMessageContaining("A RewritePath that sets the query (a ? in its replacement) must be the only RewritePath on the route");

        assertThat(build("""
                routes:
                  - id: r
                    match:
                      - PathPrefix:
                          prefix: /a
                    filters:
                      - RewritePath:
                          regexp: "^/a/(.*)$"
                          replacement: "/$1"
                      - RewritePath:
                          regexp: "^/a/x$"
                          replacement: "/y"
                    upstream:
                      targets:
                        - url: http://localhost:1
                """).routes()).hasSize(1);
    }

    private static final String BREAKER = """
            - CircuitBreaker:
                failure_threshold: 5
                cooldown_period: 30s
            """;

    /**
     * A breaker guards one upstream; at the root or on a group it would be one breaker for every
     * route beneath, so it is a route's filter only.
     */
    @Test
    void aCircuitBreakerAboveRouteLevelIsRefused() throws IOException
    {
        assertThatThrownBy(() -> build("""
                filters:
                %s
                routes:
                  - id: a
                    match:
                      - PathPrefix:
                          prefix: /a
                    upstream:
                      targets:
                        - url: http://localhost:1
                """.formatted(BREAKER.indent(2))))
                .hasMessageContaining("[filters[0]] CircuitBreaker can be declared on a route only, not on the root.");

        assertThatThrownBy(() -> build("""
                routes:
                  - group: g
                    filters:
                    %s
                    upstream:
                      targets:
                        - url: http://localhost:1
                    routes:
                      - id: a
                        match:
                          - PathPrefix:
                              prefix: /a
                """.formatted(BREAKER.indent(6).stripLeading())))
                .hasMessageContaining("[routes.g.filters[0]] CircuitBreaker can be declared on a route only, not on a group.");

        assertThat(build("""
                routes:
                  - group: g
                    upstream:
                      targets:
                        - url: http://localhost:1
                    routes:
                      - id: a
                        match:
                          - PathPrefix:
                              prefix: /a
                        filters:
                        %s
                """.formatted(BREAKER.indent(10).stripLeading())).routes()).hasSize(1);
    }
}
