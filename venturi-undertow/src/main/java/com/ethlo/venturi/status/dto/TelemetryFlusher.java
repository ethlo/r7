package com.ethlo.venturi.status.dto;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.ethlo.venturi.status.TelemetryRepository;

public class TelemetryFlusher
{
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final TelemetryRepository repository;
    private final Supplier<List<RouteMetricsDto>> supplier;

    public TelemetryFlusher(final TelemetryRepository repository, Supplier<List<RouteMetricsDto>> supplier)
    {
        this.repository = repository;
        this.supplier = supplier;
    }

    public void start()
    {
        this.scheduler.scheduleAtFixedRate(this::save, 2, 2, TimeUnit.SECONDS);
    }

    private void save()
    {
        repository.save(supplier.get());
    }
}
