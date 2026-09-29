package com.ethlo.r7.config;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.GatewayRoute;

public class RouteRegistry
{
    private final AtomicReference<Snapshot> current = new AtomicReference<>(new Snapshot(null, List.of(), null));

    /**
     * One generation of the routing configuration. Published as a whole, so a request that
     * misses every route is refused under the unrouted policy of the same generation it was
     * matched against, never a mix of an old table and a new policy during a hot reload.
     *
     * @param unrouted the journal-only route for requests refused before routing, or
     *                 {@code null} if such requests are not journaled
     */
    public record Snapshot(String version, List<GatewayRoute> routes, GatewayRoute unrouted)
    {
        public GatewayRoute findRoute(final GatewayRequest gatewayRequest)
        {
            for (final GatewayRoute route : this.routes)
            {
                if (route.predicate().test(gatewayRequest))
                {
                    return route;
                }
            }
            return null;
        }
    }

    /**
     * Swaps the current routing table for a new one for hot-reload
     */
    public void updateRoutes(final String version, final List<GatewayRoute> newRoutes)
    {
        updateRoutes(version, newRoutes, null);
    }

    /**
     * @param unroutedRoute the route requests refused before routing are journaled under, or
     *                      {@code null} when the configuration has no {@code unrouted} section
     */
    public void updateRoutes(final String version, final List<GatewayRoute> newRoutes, final GatewayRoute unroutedRoute)
    {
        this.current.set(new Snapshot(version, List.copyOf(newRoutes), unroutedRoute));
    }

    /**
     * @return the current generation; hold on to it for the whole of a routing decision
     */
    public Snapshot snapshot()
    {
        return this.current.get();
    }

    public GatewayRoute findRoute(GatewayRequest gatewayRequest)
    {
        return this.current.get().findRoute(gatewayRequest);
    }

    public List<GatewayRoute> getRoutes()
    {
        return this.current.get().routes();
    }

    public String getConfigVersion()
    {
        return this.current.get().version();
    }

    /**
     * @return the journal-only route for requests refused before routing, or {@code null} if
     * such requests are not journaled
     */
    public GatewayRoute unroutedRoute()
    {
        return this.current.get().unrouted();
    }

    public Optional<GatewayRoute> findRoute(String routeId)
    {
        return this.current.get().routes().stream().filter(route -> route.id().equals(routeId)).findFirst();
    }
}
