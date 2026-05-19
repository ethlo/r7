package com.ethlo.r7.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.config.HealthCheckConfig;
import com.ethlo.r7.config.HealthCheckConfig.TargetStateOverride;

public final class PeriodicUpstreamHealthMonitor implements UpstreamHealthMonitor
{
    private final Set<URI> allTargets;
    private final Set<URI> healthyTargets;
    private final HttpClient httpClient;
    private final UpstreamTargetObserver targetObserver;
    private final HealthCheckConfig config;
    private final Map<URI, Integer> consecutiveSuccesses;
    private final Map<URI, Integer> consecutiveFailures;
    private final Map<URI, TargetStateOverride> overrides;
    private java.util.concurrent.ScheduledFuture<?> scheduledTask;
    public PeriodicUpstreamHealthMonitor(final Set<URI> targets, final UpstreamTargetObserver targetObserver, final HealthCheckConfig config)
    {
        this.targetObserver = targetObserver;
        this.allTargets = Set.copyOf(targets);
        this.config = config;
        this.healthyTargets = ConcurrentHashMap.newKeySet();

        this.consecutiveSuccesses = new ConcurrentHashMap<>();
        this.consecutiveFailures = new ConcurrentHashMap<>();
        this.overrides = new ConcurrentHashMap<>();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        for (final URI target : targets)
        {
            // Prime the counters assuming healthy, just in case there is no override
            this.consecutiveSuccesses.put(target, this.config.rise());
            this.consecutiveFailures.put(target, 0);
            this.overrides.put(target, this.config.override());

            // Let the state machine evaluate the initial state properly!
            // If FORCE_DOWN is set, shouldBeUp will evaluate to false,
            this.evaluateAndApplyState(target);
        }
    }

    public void start(final GatewayScheduler scheduler)
    {
        this.scheduledTask = scheduler.scheduleEvery(this.config.interval(), this::pingAll);
    }

    @Override
    public void stop()
    {
        if (this.scheduledTask != null)
        {
            this.scheduledTask.cancel(false);
        }
    }

    public void setOverride(final URI target, final TargetStateOverride override)
    {
        if (this.allTargets.contains(target))
        {
            this.overrides.put(target, override);
            this.evaluateAndApplyState(target);
        }
    }

    private void pingAll()
    {
        final String safePath = config.path().startsWith("/") ? config.path() : "/" + config.path();

        for (final URI target : this.allTargets)
        {
            if (this.overrides.get(target) != TargetStateOverride.NONE)
            {
                continue;
            }

            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(target.resolve(safePath))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();

            this.httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(res -> this.processProbeResult(target, res.statusCode() == 200))
                    .exceptionally(ex ->
                    {
                        this.processProbeResult(target, false);
                        return null;
                    });
        }
    }

    private void processProbeResult(final URI target, final boolean probeSuccessful)
    {
        if (probeSuccessful)
        {
            this.consecutiveFailures.put(target, 0);
            this.consecutiveSuccesses.merge(target, 1, Integer::sum);
        }
        else
        {
            this.consecutiveSuccesses.put(target, 0);
            this.consecutiveFailures.merge(target, 1, Integer::sum);
        }

        this.evaluateAndApplyState(target);
    }

    private synchronized void evaluateAndApplyState(final URI target)
    {
        final TargetStateOverride override = this.overrides.get(target);
        final boolean shouldBeUp;

        if (override == TargetStateOverride.FORCE_UP)
        {
            shouldBeUp = true;
        }
        else if (override == TargetStateOverride.FORCE_DOWN)
        {
            shouldBeUp = false;
        }
        else
        {
            final int successes = this.consecutiveSuccesses.getOrDefault(target, 0);
            final int failures = this.consecutiveFailures.getOrDefault(target, 0);

            if (successes >= this.config.rise())
            {
                shouldBeUp = true;
            }
            else if (failures >= this.config.fall())
            {
                shouldBeUp = false;
            }
            else
            {
                shouldBeUp = this.healthyTargets.contains(target);
            }
        }

        if (shouldBeUp && !this.healthyTargets.contains(target))
        {
            this.healthyTargets.add(target);
            this.targetObserver.onTargetUp(target);
        }
        else if (!shouldBeUp && this.healthyTargets.contains(target))
        {
            this.healthyTargets.remove(target);
            this.targetObserver.onTargetDown(target);
        }
    }

    @Override
    public boolean hasAvailableTargets()
    {
        return !this.healthyTargets.isEmpty();
    }
}