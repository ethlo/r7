package com.ethlo.venturi.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.config.RouteRegistry;
import com.ethlo.venturi.config.VenturiLoader;

class VenturiIntegrationTest
{
    @RepeatedTest(5)
    void testLoadCompleteConfiguration(@TempDir final Path tempDir) throws IOException
    {
        // 1. Prepare a complex YAML configuration
        final String yaml = """
                routes:
                  - id: secure-api
                    uri: http://internal-service:8080
                    match:
                      and:
                        - pathStartsWith: /v1
                        - method: POST
                        - header:
                            name: X-Secret
                            value: true
                    audit:
                      request: FULL
                      response: HEADERS
                    filters:
                      - type: AddResponseHeader
                        args:
                          name: X-Gateway
                          value: Venturi
                """;

        final Path configFile = tempDir.resolve("venturi.yaml");
        Files.writeString(configFile, yaml);

        // 2. Initialize the Venturi components
        final RouteRegistry registry = new RouteRegistry();
        final VenturiLoader loader = new VenturiLoader();

        // 3. Execute the load (The "Live Reload" entry point)
        // FIXME: loader.load(configFile, registry, s->);

        // 4. Assert the Registry state
        // We look for our "secure-api" route
        final List<GatewayRoute> routes = getRoutesFromRegistry(registry);
        assertThat(routes).hasSize(1);

        final GatewayRoute route = routes.getFirst();
        assertThat(route.id()).hasToString("secure-api");
        assertThat(route.uri()).containsExactly("http://internal-service:8080");

        // 5. Assert Predicate Compilation
        // The predicate should be a composite 'and' tree
        assertThat(route.predicate()).isNotNull();

        // 6. Assert Filter Chain Construction
        // We expect 2 filters: 1 Audit filter (injected) and 1 AddRequestHeader
        final List<GatewayFilter> filters = (List<GatewayFilter>) route.filters();
        assertThat(filters).hasSize(2);

        // First filter should be the Audit filter setting the attributes
        final GatewayFilter auditFilter = filters.get(0);
        assertThat(auditFilter.getClass().getSimpleName()).isEmpty(); // Anonymous inner class from FilterBuilder

        // Second filter should be our custom transformation
        final GatewayFilter transformFilter = filters.get(1);
        assertThat(transformFilter).isNotNull();
    }

    /**
     * Helper to peek into the AtomicReference in the registry for testing.
     */
    @SuppressWarnings("unchecked")
    private List<GatewayRoute> getRoutesFromRegistry(final RouteRegistry registry)
    {
        try
        {
            final var field = RouteRegistry.class.getDeclaredField("routes");
            field.setAccessible(true);
            final var atomicRef = (java.util.concurrent.atomic.AtomicReference<List<GatewayRoute>>) field.get(registry);
            return atomicRef.get();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}