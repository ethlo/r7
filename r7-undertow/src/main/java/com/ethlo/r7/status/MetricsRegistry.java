package com.ethlo.r7.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ethlo.r7.config.RouteDefinition;

public final class MetricsRegistry
{
    private final Map<String, SimpleMetricsFactory.GF> routeMetrics = new ConcurrentHashMap<>();
    private final List<RouteDefinition> routes = new ArrayList<>();

    public void register(final RouteDefinition route, final SimpleMetricsFactory.GF metrics)
    {
        this.routes.add(route);
        if (metrics != null)
        {
            routeMetrics.put(route.id().toString(), metrics);
        }
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