package com.ethlo.venturi.config;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.core.ExecutableRoute;

public class RouteRegistry
{
    private final AtomicReference<List<ExecutableRoute>> routes = new AtomicReference<>(Collections.emptyList());

    /**
     * Swaps the current routing table for a new one. (Live Reload)
     */
    public void updateRoutes(List<ExecutableRoute> newRoutes)
    {
        this.routes.set(List.copyOf(newRoutes));
    }

    /**
     * Finds the first matching route. High-performance O(N) linear scan.
     */
    public Optional<ExecutableRoute> findRoute(GatewayRequest exchange)
    {
        final List<ExecutableRoute> current = routes.get();
        for (ExecutableRoute route : current)
        {
            // This calls our zero-allocation predicates compiled from YAML
            if (route.predicate().test(exchange))
            {
                return Optional.of(route);
            }
        }
        return Optional.empty();
    }
}