package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.util.TestFingerprints;

/**
 * M5: a global filter is declared once, in {@code global_filters}, precisely so every route
 * shares its state - one RateLimiter bucket set across the whole gateway, one CircuitBreaker per
 * upstream regardless of how many routes lead to it. Instantiating it again for every route it
 * gets attached to (as {@link ConfigurationManager#build} once did, inside the per-route
 * transformation loop) silently multiplies the configured limit by the route count instead.
 */
class GlobalFilterSharingTest
{
    @TempDir
    Path dir;

    private List<GatewayRoute> load(final String globalFilters, final String... routes) throws IOException
    {
        final Path file = this.dir.resolve("routes.yaml");
        Files.writeString(file, "version: test\n" + globalFilters + "routes:\n" + String.join("", routes));
        final RoutesDefinition definition = ConfigurationManager.load(file, RoutesDefinition.class);
        final RouteRegistry registry = new RouteRegistry();
        new ConfigurationManager(TestFingerprints.engine()).load(definition, registry);
        return registry.getRoutes();
    }

    private static String route(final String id)
    {
        return """
                  - id: %s
                    match:
                      - PathPrefix:
                          prefix: /%s
                    upstream:
                      targets:
                        - url: http://localhost:1
                """.formatted(id, id);
    }

    @Test
    void aGlobalRateLimiterIsTheSameInstanceOnEveryRoute() throws IOException
    {
        final String globals = """
                global_filters:
                  - RateLimiter:
                      capacity: 1
                      refill_tokens: 1
                      refill_period: 1h
                """;

        final List<GatewayRoute> routes = load(globals, route("a"), route("b"), route("c"));
        assertThat(routes).hasSize(3);

        // Every route's global filter slot (index 0, since global filters are prepended) must be
        // the very same object: only then does one client's request against route "a" consume
        // from the same bucket a request against "b" or "c" would also draw from.
        final GatewayFilter first = routes.get(0).filters().get(0);
        for (final GatewayRoute route : routes)
        {
            assertThat(route.filters().get(0)).isSameAs(first);
        }
    }

    @Test
    void aRouteLevelRateLimiterIsStillPerRoute() throws IOException
    {
        // A filter declared under a route's own `filters:` (not `global_filters:`) has always
        // been route-scoped and must stay that way: only the global-filter sharing changed.
        final String routeWithOwnLimiter = """
                  - id: solo
                    match:
                      - PathPrefix:
                          prefix: /solo
                    filters:
                      - RateLimiter:
                          capacity: 1
                          refill_tokens: 1
                          refill_period: 1h
                    upstream:
                      targets:
                        - url: http://localhost:1
                """;

        final List<GatewayRoute> routes = load("", routeWithOwnLimiter, route("other"));
        assertThat(routes.get(0).filters()).hasSize(1);
        assertThat(routes.get(1).filters()).isEmpty();
    }
}
