package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    /**
     * The matched route's own filters: one whose request phase ran (Cors) must still get its
     * response phase after a fallback, on the same instance; one that only has an upstream phase
     * (AddRequestHeader) must not run at all, since it shaped a request for a different upstream.
     */
    @Test
    void fallbackKeepsTheLaterPhasesOfTheMatchedRoutesStartedFilters() throws IOException
    {
        final String primaryWithOwnFilters = route("a", "b").replace("    upstream:",
                "    filters:\n      - Cors:\n          allowed_origins:\n            - https://app.example\n          allowed_methods:\n            - GET\n      - AddRequestHeader:\n          name: X-Primary\n          value: \"1\"\n    upstream:");
        final RouteRegistry registry = loadWithGlobals("", primaryWithOwnFilters, route("b", null));
        final DefaultGatewayRoute a = (DefaultGatewayRoute) registry.findRoute("a").orElseThrow();
        final DefaultGatewayRoute b = (DefaultGatewayRoute) registry.findRoute("b").orElseThrow();

        final DefaultGatewayRoute bAfterA = a.asFallbackOfThis(b);

        final Object cors = a.clientRequestFilters()[0];
        assertThat(bAfterA.clientRequestFilters()).isEmpty();
        assertThat(bAfterA.beforeUpstreamGatewayFilters()).isEmpty();
        assertThat(bAfterA.beforeCommitGatewayFilters()).containsExactly((com.ethlo.r7.api.ClientResponseGatewayFilter) cors);

        // Carried on down a chain: c after (b after a) still owes a's Cors its response phase
        final String c = route("c", null);
        final RouteRegistry chain = loadWithGlobals("", primaryWithOwnFilters, route("b", "c"), c);
        final DefaultGatewayRoute ca = (DefaultGatewayRoute) chain.findRoute("a").orElseThrow();
        final DefaultGatewayRoute cb = (DefaultGatewayRoute) chain.findRoute("b").orElseThrow();
        final DefaultGatewayRoute cc = (DefaultGatewayRoute) chain.findRoute("c").orElseThrow();
        final DefaultGatewayRoute cAfterBAfterA = ca.asFallbackOfThis(cb).asFallbackOfThis(cc);
        assertThat(cAfterBAfterA.beforeCommitGatewayFilters()).containsExactly((com.ethlo.r7.api.ClientResponseGatewayFilter) ca.clientRequestFilters()[0]);
    }

    /**
     * A started filter that also has an upstream phase - BasicAuth removing the client's verified
     * credentials - must get that phase on the fallback's upstream too.
     */
    @Test
    void aStartedFiltersUpstreamPhaseRunsOnTheFallbackUpstream() throws IOException
    {
        final RouteRegistry registry = load(route("a", "b"), route("b", null));
        final DefaultGatewayRoute loadedA = (DefaultGatewayRoute) registry.findRoute("a").orElseThrow();
        final DefaultGatewayRoute b = (DefaultGatewayRoute) registry.findRoute("b").orElseThrow();

        final StartedWithUpstreamPhase started = new StartedWithUpstreamPhase();
        final com.ethlo.r7.api.UpstreamRequestGatewayFilter upstreamOnly = new UpstreamOnly();
        final DefaultGatewayRoute a = new DefaultGatewayRoute(List.of(), loadedA.predicate(), List.of(started, upstreamOnly), 0, loadedA.journal(), loadedA.routeDefinition());

        final DefaultGatewayRoute bAfterA = a.asFallbackOfThis(b);

        assertThat(bAfterA.clientRequestFilters()).isEmpty();
        assertThat(bAfterA.beforeUpstreamGatewayFilters()).containsExactly(started);
    }

    private static final class StartedWithUpstreamPhase implements com.ethlo.r7.api.ClientRequestGatewayFilter, com.ethlo.r7.api.UpstreamRequestGatewayFilter
    {
        @Override
        public void onClientRequest(final com.ethlo.r7.api.ClientRequestGatewayExchange exchange)
        {
        }

        @Override
        public void onUpstreamRequest(final com.ethlo.r7.api.UpstreamRequestGatewayExchange exchange)
        {
        }

        @Override
        public String name()
        {
            return "started";
        }

        @Override
        public String summary()
        {
            return "started";
        }
    }

    private static final class UpstreamOnly implements com.ethlo.r7.api.UpstreamRequestGatewayFilter
    {
        @Override
        public void onUpstreamRequest(final com.ethlo.r7.api.UpstreamRequestGatewayExchange exchange)
        {
        }

        @Override
        public String name()
        {
            return "upstream-only";
        }

        @Override
        public String summary()
        {
            return "upstream-only";
        }
    }
}
