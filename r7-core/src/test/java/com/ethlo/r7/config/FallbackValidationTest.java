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

import com.ethlo.r7.spi.EngineContext;

class FallbackValidationTest
{
    @TempDir
    Path dir;

    private static String route(final String id, final String fallback)
    {
        return """
                  - id: %s
                    match:
                      - PathPrefix:
                          prefix: /%s
                    upstream:
                      targets:
                        - url: http://localhost:1
                %s
                """.formatted(id, id, fallback == null ? "" : """
                      fallback:
                        route_id: %s
                """.formatted(fallback));
    }

    private RouteRegistry load(final String... routes) throws IOException
    {
        return loadWithGlobals("", routes);
    }

    private RouteRegistry loadWithGlobals(final String globalFilters, final String... routes) throws IOException
    {
        final Path file = this.dir.resolve("routes.yaml");
        Files.writeString(file, "version: test\n" + globalFilters + "routes:\n" + String.join("", routes));
        final RoutesDefinition definition = ConfigurationManager.load(file, RoutesDefinition.class);
        final RouteRegistry registry = new RouteRegistry();
        new ConfigurationManager(new EngineContext(Map.of())).load(definition, registry);
        return registry;
    }

    @Test
    void acceptsAFallbackChain()
    {
        assertThatCode(() -> load(route("a", "b"), route("b", "c"), route("c", null))).doesNotThrowAnyException();
    }

    @Test
    void rejectsATwoRouteCycle()
    {
        assertThatThrownBy(() -> load(route("a", "b"), route("b", "a")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void rejectsALongerCycleAndNamesIt()
    {
        assertThatThrownBy(() -> load(route("entry", "a"), route("a", "b"), route("b", "c"), route("c", "a")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("a -> b -> c -> a");
    }

    @Test
    void stillRejectsSelfReference()
    {
        assertThatThrownBy(() -> load(route("a", "a")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("itself");
    }

    /**
     * Global filters are instantiated per route. A request's global request phase runs on the
     * instances of the route it matched, so after a fallback its response and completion phases
     * must run on those same instances, not the fallback route's copies.
     */
    @Test
    void fallbackRunsWithTheMatchedRoutesGlobalFilterInstances() throws IOException
    {
        final String globals = """
                global_filters:
                  - RequestSizeLimit:
                      max_size: 1MB
                  - SetResponseHeader:
                      name: X-Global
                      value: "1"
                """;
        final String fallbackWithOwnFilters = route("b", null).replace("    upstream:",
                "    filters:\n      - SetResponseHeader:\n          name: X-Fallback\n          value: \"1\"\n    upstream:");
        final RouteRegistry registry = loadWithGlobals(globals, route("a", "b"), fallbackWithOwnFilters);
        final DefaultGatewayRoute a = (DefaultGatewayRoute) registry.findRoute("a").orElseThrow();
        final DefaultGatewayRoute b = (DefaultGatewayRoute) registry.findRoute("b").orElseThrow();

        final DefaultGatewayRoute bAfterA = a.asFallbackOfThis(b);

        // Request phase: only the global, and it is a's instance
        assertThat(bAfterA.globalClientRequestFilterCount()).isEqualTo(1);
        assertThat(bAfterA.clientRequestFilters()[0]).isSameAs(a.clientRequestFilters()[0]);
        // Response phase: a's global instance, then b's own filter
        assertThat(bAfterA.beforeCommitGatewayFilters()).hasSize(2);
        assertThat(bAfterA.beforeCommitGatewayFilters()[0]).isSameAs(a.beforeCommitGatewayFilters()[0]);
        assertThat(bAfterA.beforeCommitGatewayFilters()[1]).isSameAs(b.beforeCommitGatewayFilters()[1]);
        // Built once, and b itself is untouched
        assertThat(a.asFallbackOfThis(b)).isSameAs(bAfterA);
        assertThat(b.beforeCommitGatewayFilters()[0]).isNotSameAs(a.beforeCommitGatewayFilters()[0]);
    }
}
