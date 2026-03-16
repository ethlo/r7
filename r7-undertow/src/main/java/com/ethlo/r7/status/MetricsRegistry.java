package com.ethlo.r7.status;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import com.ethlo.r7.config.RouteRegistry;
import com.ethlo.r7.status.dto.ModelMapper;
import com.ethlo.r7.status.dto.RouteMetricsDto;
import com.ethlo.r7.status.dto.TelemetryFlusher;

public final class MetricsRegistry
{
    private final RouteRegistry routeRegistry;
    private final ConcurrentMap<String, RouteMetricsBucket> persistentStore = new ConcurrentHashMap<>();
    private final AtomicReference<List<RouteMetricsDto>> latest = new AtomicReference<>(List.of());

    public MetricsRegistry(final RouteRegistry routeRegistry, final TelemetryRepository telemetryRepository)
    {
        this.routeRegistry = routeRegistry;

        // 1. Hydrate the persistent store from disk
        final List<RouteMetricsDto> existingMetrics = telemetryRepository.load();
        for (final RouteMetricsDto dto : existingMetrics)
        {
            final RouteMetricsBucket bucket = new RouteMetricsBucket(150, java.time.Duration.ofSeconds(2));
            bucket.hydrateFromDto(dto);
            this.persistentStore.put(dto.id(), bucket);
        }

        // 2. Setup the Flusher and Matchmaker
        final TelemetryFlusher telemetryFlusher = new TelemetryFlusher(telemetryRepository, () ->
        {
            linkActiveFilters();

            final List<RouteMetricsDto> snapshot = this.persistentStore.entrySet()
                    .stream()
                    .map(entry -> ModelMapper.routeMetrics(entry)) // Note: Adjust ModelMapper to accept RouteMetricsBucket
                    .toList();

            this.latest.set(snapshot);
            return snapshot;
        });

        telemetryFlusher.start();
    }

    public List<RouteMetricsDto> getAll()
    {
        return this.latest.get();
    }

    private void linkActiveFilters()
    {
        for (final var route : this.routeRegistry.getRoutes())
        {
            final String routeId = route.id().toString();

            // Find the active GF filter for this route
            route.filters().stream()
                    .filter(f -> f instanceof SimpleMetricsFactory.GF)
                    .map(SimpleMetricsFactory.GF.class::cast)
                    .findFirst()
                    .ifPresent(activeFilter ->
                    {
                        // Ensure a persistent bucket exists for this route
                        final RouteMetricsBucket persistentBucket = this.persistentStore.computeIfAbsent(
                                routeId, k -> new RouteMetricsBucket(150, java.time.Duration.ofSeconds(2))
                        );

                        // Hot-swap the filter's temporary bucket with the persistent one
                        activeFilter.linkPersistentBucket(persistentBucket);
                    });
        }
    }
}