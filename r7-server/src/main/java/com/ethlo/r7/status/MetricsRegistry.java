package com.ethlo.r7.status;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.status.dto.ModelMapper;
import com.ethlo.r7.status.dto.RouteMetricsDto;
import com.ethlo.r7.status.dto.TelemetryFlusher;

public final class MetricsRegistry
{
    private final ConcurrentMap<String, RouteMetricsBucket> persistentStore = new ConcurrentHashMap<>();

    // The Holding Pen: Stores historical disk data until the YAML parser claims it
    private final ConcurrentMap<String, RouteMetricsDto> pendingHydration = new ConcurrentHashMap<>();

    private final AtomicReference<List<RouteMetricsDto>> latest = new AtomicReference<>(List.of());
    private final GatewayScheduler scheduler;

    public MetricsRegistry(final TelemetryRepository telemetryRepository, final GatewayScheduler scheduler)
    {
        this.scheduler = scheduler;

        // Do not instantiate buckets or schedule tasks yet. YAML is the boss.
        for (final RouteMetricsDto dto : telemetryRepository.load())
        {
            this.pendingHydration.put(dto.id(), dto);
        }

        final TelemetryFlusher telemetryFlusher = new TelemetryFlusher(telemetryRepository, () ->
        {
            final List<RouteMetricsDto> snapshot = this.persistentStore.entrySet()
                    .stream()
                    .map(ModelMapper::routeMetrics)
                    .toList();

            this.latest.set(snapshot);
            return snapshot;
        }
        );

        telemetryFlusher.start();
    }

    public RouteMetricsBucket getOrCreate(final String routeId, final int capacity, final Duration interval)
    {
        return this.persistentStore.computeIfAbsent(routeId, k ->
                {
                    // Create the bucket using the exact capacity and interval from the YAML config
                    final RouteMetricsBucket newBucket = new RouteMetricsBucket(capacity, interval);

                    // Check if we have historical data waiting to be restored for this route
                    final RouteMetricsDto history = this.pendingHydration.remove(routeId);
                    if (history != null)
                    {
                        newBucket.hydrateFromDto(history);
                    }

                    // Schedule the background tick exactly once
                    this.scheduler.scheduleEvery(interval, newBucket::triggerSparkline);

                    return newBucket;
                }
        );
    }

    public List<RouteMetricsDto> getAll()
    {
        return this.latest.get();
    }
}