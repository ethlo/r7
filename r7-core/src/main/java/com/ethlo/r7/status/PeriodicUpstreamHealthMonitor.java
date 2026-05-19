package com.ethlo.r7.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.ethlo.r7.GatewayScheduler;

public final class PeriodicUpstreamHealthMonitor implements UpstreamHealthMonitor
{
    private final Set<URI> allTargets;
    private final Set<URI> healthyTargets;
    private final HttpClient httpClient;
    private final UpstreamTargetObserver targetObserver;
    private final String healthPath;

    public PeriodicUpstreamHealthMonitor(final Set<URI> targets, final UpstreamTargetObserver targetObserver, final String healthPath)
    {
        this.targetObserver = targetObserver;
        this.allTargets = Set.copyOf(targets);
        this.healthyTargets = ConcurrentHashMap.newKeySet();

        // Ensure the path begins with a slash for safe URI resolution
        this.healthPath = healthPath.startsWith("/") ? healthPath : "/" + healthPath;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    public void start(final GatewayScheduler scheduler, final Duration checkInterval)
    {
        scheduler.scheduleEvery(checkInterval, this::pingAll);
    }

    private void pingAll()
    {
        for (final URI target : this.allTargets)
        {
            // Resolve the health path against the base target URI
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(target.resolve(this.healthPath))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();

            this.httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(res -> this.updateTarget(target, res.statusCode() == 200))
                    .exceptionally(ex ->
                    {
                        this.updateTarget(target, false);
                        return null;
                    });
        }
    }

    private synchronized void updateTarget(final URI target, final boolean isHealthy)
    {
        if (isHealthy)
        {
            if (this.healthyTargets.add(target))
            {
                // Signal the engine that the node is back online
                this.targetObserver.onTargetUp(target);
            }
        }
        else
        {
            if (this.healthyTargets.remove(target))
            {
                // Signal the engine to drop the node
                this.targetObserver.onTargetDown(target);
            }
        }
    }

    @Override
    public boolean hasAvailableTargets()
    {
        return !this.healthyTargets.isEmpty();
    }
}