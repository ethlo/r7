package com.ethlo.r7.config;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.GatewayRoute;

public class RouteRegistry
{
    private final AtomicReference<List<GatewayRoute>> routes = new AtomicReference<>(Collections.emptyList());
    private final AtomicReference<GatewayRoute> unrouted = new AtomicReference<>();
    private String version;

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
        this.version = version;
        this.unrouted.set(unroutedRoute);
        this.routes.set(List.copyOf(newRoutes));
    }

    /**
     * @return the journal-only route for requests refused before routing, or {@code null} if
     * such requests are not journaled
     */
    public GatewayRoute unroutedRoute()
    {
        return this.unrouted.get();
    }

    public GatewayRoute findRoute(GatewayRequest gatewayRequest)
    {
        final List<GatewayRoute> current = routes.get();
        for (GatewayRoute route : current)
        {
            if (route.predicate().test(gatewayRequest))
            {
                return route;
            }
        }
        return null;
    }

    public List<GatewayRoute> getRoutes()
    {
        return this.routes.get();
    }

    public String getConfigVersion()
    {
        return version;
    }

    public Optional<GatewayRoute> findRoute(String routeId)
    {
        return routes.get().stream().filter(route -> route.id().equals(routeId)).findFirst();
    }
}