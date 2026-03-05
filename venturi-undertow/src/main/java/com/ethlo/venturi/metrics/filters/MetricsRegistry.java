package com.ethlo.venturi.metrics.filters;

import com.ethlo.venturi.config.RouteDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MetricsRegistry
{
    private final Map<String, SimpleMetricsFactory.GF> routeMetrics = new ConcurrentHashMap<>();
    private final List<RouteDefinition> routes = new ArrayList<>();

    public void register(final RouteDefinition route, final SimpleMetricsFactory.GF metrics)
    {
        this.routes.add(route);
        routeMetrics.put(route.id().toString(), metrics);
    }

    public Map<String, SimpleMetricsFactory.GF> getAll()
    {
        return routeMetrics;
    }

    public List<RouteDefinition> getRouteDefinitions()
    {
        return routes;
    }
}