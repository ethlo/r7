package com.ethlo.r7.status;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.status.dto.ModelMapper;
import com.ethlo.r7.status.dto.RouteMetricsDto;

/**
 * Route metrics, published to the management endpoint and saved to the work directory every
 * {@link #FLUSH_INTERVAL} on the gateway's scheduler, so it owns no thread of its own: a gateway
 * that is closed, as a servlet container does on every redeploy, leaves nothing running.
 * {@link #close} saves once more, so a restart resumes from the counts at shutdown.
 */
public final class MetricsRegistry implements AutoCloseable
{
    static final Duration FLUSH_INTERVAL = Duration.ofSeconds(2);

    private static final Logger logger = LoggerFactory.getLogger(MetricsRegistry.class);

    private final ConcurrentMap<String, RouteMetricsBucket> persistentStore = new ConcurrentHashMap<>();

    // The Holding Pen: Stores historical disk data until the YAML parser claims it
    private final ConcurrentMap<String, RouteMetricsDto> pendingHydration = new ConcurrentHashMap<>();

    private final AtomicReference<List<RouteMetricsDto>> latest = new AtomicReference<>(List.of());
    private final GatewayScheduler scheduler;
    private final TelemetryRepository telemetryRepository;
    private final ScheduledFuture<?> flushing;

    public MetricsRegistry(final TelemetryRepository telemetryRepository, final GatewayScheduler scheduler)
    {
        this.scheduler = scheduler;
        this.telemetryRepository = telemetryRepository;

        // Do not instantiate buckets or schedule tasks yet. YAML is the boss.
        for (final RouteMetricsDto dto : telemetryRepository.load())
        {
            this.pendingHydration.put(dto.id(), dto);
        }

        this.flushing = scheduler.scheduleEvery(FLUSH_INTERVAL, this::flush);
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

    /**
     * Publishes a snapshot to {@link #getAll} and saves it. Synchronized so that the final save
     * in {@link #close} cannot interleave with a scheduled one still running.
     */
    synchronized void flush()
    {
        final List<RouteMetricsDto> snapshot = this.persistentStore.entrySet()
                .stream()
                .map(ModelMapper::routeMetrics)
                .toList();
        this.latest.set(snapshot);
        this.telemetryRepository.save(snapshot);
    }

    /**
     * Stops the periodic flush and saves a last time.
     */
    @Override
    public void close()
    {
        this.flushing.cancel(false);
        try
        {
            flush();
        }
        catch (final RuntimeException e)
        {
            logger.warn("Could not save route metrics on shutdown", e);
        }
    }
}
