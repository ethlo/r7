package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.GatewayRoute;

/**
 * A hot reload publishes the route table and the unrouted policy together: a request holding a
 * snapshot sees one generation of both, never the old table with the new policy.
 */
class RouteRegistryTest
{
    @Test
    void aSnapshotKeepsItsGenerationAcrossAReload()
    {
        final RouteRegistry registry = new RouteRegistry();
        final GatewayRoute oldRoute = mock(GatewayRoute.class);
        final GatewayRoute oldUnrouted = mock(GatewayRoute.class);
        registry.updateRoutes("v1", List.of(oldRoute), oldUnrouted);

        final RouteRegistry.Snapshot held = registry.snapshot();
        registry.updateRoutes("v2", List.of(), null);

        assertThat(held.version()).isEqualTo("v1");
        assertThat(held.routes()).containsExactly(oldRoute);
        assertThat(held.unrouted()).isSameAs(oldUnrouted);

        final RouteRegistry.Snapshot now = registry.snapshot();
        assertThat(now.version()).isEqualTo("v2");
        assertThat(now.routes()).isEmpty();
        assertThat(now.unrouted()).isNull();
    }
}
