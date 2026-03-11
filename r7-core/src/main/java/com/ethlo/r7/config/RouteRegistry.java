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

    /**
     * Swaps the current routing table for a new one for hot-reload
     */
    public void updateRoutes(List<GatewayRoute> newRoutes)
    {
        this.routes.set(List.copyOf(newRoutes));
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
}