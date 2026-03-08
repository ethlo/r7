package com.ethlo.venturi.config;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.api.GatewayRoute;

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

    public GatewayRoute findRoute(GatewayRequest exchange)
    {
        final List<GatewayRoute> current = routes.get();
        for (GatewayRoute route : current)
        {
            if (route.predicate().test(exchange))
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