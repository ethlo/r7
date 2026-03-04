package com.ethlo.venturi.metrics.filters;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MetricsRegistry
{
    private final Map<String, SimpleMetricsFactory.GF> routeMetrics = new ConcurrentHashMap<>();

    public void register(final String routeId, final SimpleMetricsFactory.GF metrics)
    {
        routeMetrics.put(routeId, metrics);
    }

    public Map<String, SimpleMetricsFactory.GF> getAll()
    {
        return routeMetrics;
    }
}