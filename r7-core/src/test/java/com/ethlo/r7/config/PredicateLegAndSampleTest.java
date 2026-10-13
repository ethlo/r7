package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.condition.ConditionInput;
import com.ethlo.r7.spi.EngineContext;

class PredicateLegAndSampleTest
{
    @TempDir
    Path dir;

    private final ConfigurationManager manager = new ConfigurationManager(new EngineContext(Map.of()));

    private RouteRegistry.Snapshot build(final String match) throws Exception
    {
        final Path file = this.dir.resolve("routes.yaml");
        Files.writeString(file, """
                version: test
                routes:
                  - id: x
                    match:
                %s
                    upstream:
                      targets:
                        - url: http://localhost:1
                """.formatted(match.stripTrailing().indent(6).stripTrailing()));
        return this.manager.build(ConfigurationManager.load(file, RoutesDefinition.class));
    }

    private static ConditionInput request(final String path)
    {
        final GatewayRequest request = mock(GatewayRequest.class);
        when(request.path()).thenReturn(path);
        when(request.uri()).thenReturn(path);
        return ConditionInput.of(request);
    }

    @Test
    void theClientLegIsTheDefaultAndMayBeNamed() throws Exception
    {
        final RouteRegistry.Snapshot snapshot = build("""
                - PathPrefix:
                    prefix: /api
                    leg: client
                """);
        assertThat(snapshot.findRoute(request("/api/1"))).extracting(GatewayRoute::id).isEqualTo("x");
        assertThat(snapshot.findRoute(request("/other"))).isNull();
    }

    @Test
    void theUpstreamLegIsRefusedWhenARouteIsChosen()
    {
        assertThatThrownBy(() -> build("""
                - PathPrefix:
                    prefix: /api
                    leg: upstream
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("[routes.x.match[0].PathPrefix.leg] The upstream leg does not exist yet when a route is chosen; leg: upstream is allowed only in on_response.");
    }

    @Test
    void anUnknownLegIsRefused()
    {
        assertThatThrownBy(() -> build("""
                - not:
                    PathPrefix:
                      prefix: /api
                      leg: sideways
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("[routes.x.match[0].not.PathPrefix.leg] Must be client or upstream, received 'sideways'");
    }

    @Test
    void sampleSendsAShareOfTrafficToACanaryRoute() throws Exception
    {
        final RouteRegistry.Snapshot all = build("""
                - PathPrefix:
                    prefix: /api
                - Sample:
                    rate: 1
                """);
        assertThat(all.findRoute(request("/api/1"))).extracting(GatewayRoute::id).isEqualTo("x");
        assertThat(all.findRoute(request("/other"))).isNull();
    }

    @Test
    void sampleRefusesARateOutsideItsRangeAndALeg()
    {
        assertThatThrownBy(() -> build("""
                - Sample:
                    rate: 0
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("[routes.x.match[0].Sample.rate] Must be greater than 0 and at most 1, received 0.0");
        assertThatThrownBy(() -> build("""
                - Sample:
                    rate: 0.5
                    leg: client
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("[routes.x.match[0].Sample.leg] Sample draws once per exchange and has no leg.");
    }
}
