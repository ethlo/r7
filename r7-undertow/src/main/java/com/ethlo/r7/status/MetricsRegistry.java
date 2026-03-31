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
    private final AtomicReference<List<RouteMetricsDto>> latest = new AtomicReference<>(List.of());
    private final GatewayScheduler scheduler;

    public MetricsRegistry(final TelemetryRepository telemetryRepository, final GatewayScheduler scheduler)
    {
        this.scheduler = scheduler;

        for (final RouteMetricsDto dto : telemetryRepository.load())
        {
            final SparklineRingBuffer.SparklineMetadata md = dto.sparklineData().metadata();
            final RouteMetricsBucket bucket = new RouteMetricsBucket(md.capacity(), md.interval());
            bucket.hydrateFromDto(dto);
            this.persistentStore.put(dto.id(), bucket);

            // Resume the background tick for hydrated buckets
            this.scheduler.scheduleEvery(md.interval(), bucket::triggerSparkline);
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
                    final RouteMetricsBucket newBucket = new RouteMetricsBucket(capacity, interval);

                    // Schedule the trigger exactly ONCE when the bucket is born
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