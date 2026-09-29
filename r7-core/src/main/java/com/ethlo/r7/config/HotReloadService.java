package com.ethlo.r7.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.validation.ValidationResult;

public final class HotReloadService
{
    private static final Logger log = LoggerFactory.getLogger(HotReloadService.class);

    private final Path configFilePath;
    private final ConfigurationManager configManager;
    private final RouteRegistry routeRegistry;
    private final Set<RouteGenerationListener> listeners = new LinkedHashSet<>();
    private long lastKnownModified;
    private volatile Instant loadedAt;
    private volatile Instant rejectedAt;

    public HotReloadService(final GatewayScheduler scheduler, final Path configFilePath, final ConfigurationManager configManager, final RouteRegistry routeRegistry)
    {
        this.configFilePath = configFilePath;
        this.configManager = configManager;
        this.routeRegistry = routeRegistry;
        this.lastKnownModified = System.currentTimeMillis();

        reloadPipeline(true);

        // Last, so the poller never sees a half-constructed service
        scheduler.scheduleEvery(Duration.ofSeconds(1), this::pollForChanges);
    }

    private void pollForChanges()
    {
        final long currentModified;
        try
        {
            currentModified = Files.getLastModifiedTime(configFilePath).toMillis();
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }

        if (currentModified > this.lastKnownModified)
        {
            log.debug("Detected change in configuration file {}", configFilePath.toAbsolutePath());
            this.lastKnownModified = currentModified;
            reloadPipeline(false);
        }
    }

    /**
     * Synchronized with {@link #onReload}: a listener is either added before a reload starts,
     * and prepares with it, or after it ends, and prepares the routes it published. Otherwise a
     * listener added mid-reload misses the generation that goes live, which then runs without
     * what the listener attaches to it.
     */
    synchronized void reloadPipeline(boolean initial)
    {
        try
        {
            log.info("Loading routes from {}", configFilePath.toAbsolutePath());
            RoutesDefinition routesConfig = ConfigurationManager.load(this.configFilePath, RoutesDefinition.class);

            if (routesConfig == null)
            {
                log.warn("No routes found");
                routesConfig = new RoutesDefinition(null, List.of(), List.of());
            }


            final ValidationResult validationResult = new ValidationResult();
            routesConfig.validate(validationResult);
            if (validationResult.hasErrors())
            {
                throw new ConfigurationException("routes.yaml validation failed. Errors: " + String.join(", ", validationResult.getErrors()));
            }

            final List<GatewayRoute> routes = this.configManager.build(routesConfig);
            this.prepare(routes);
            final List<GatewayRoute> previous = this.routeRegistry.getRoutes();
            this.routeRegistry.updateRoutes(routesConfig.version(), routes);
            this.loadedAt = Instant.now();
            this.rejectedAt = null;
            this.retire(previous);
            log.info("Configuration {} successfully loaded {} routes from version {}", this.configFilePath, routesConfig.routes().size(), routesConfig.version());
        }
        catch (final RuntimeException e)
        {
            if (!initial)
            {
                this.rejectedAt = Instant.now();
                log.warn("Hot reload failed. Retaining current configuration: {}", e.getMessage());
            }
            else
            {
                throw e;
            }
        }
    }

    /**
     * What the gateway is running on, for display. A rejected edit leaves the previous routes in
     * place and says so only in the log; without this an operator looking at the dashboard sees
     * the old routes and no sign that the file on disk says otherwise.
     * <p>
     * Deliberately carries no error message: validation messages can quote configured values, and
     * this is shown on a page whose viewers are not necessarily the people who write the config.
     *
     * @param rejectedAt when the most recent change to the file failed to load, or null when the
     *                   file as last read is what is running
     */
    public record Status(String file, Instant loadedAt, Instant rejectedAt)
    {
    }

    public Status status()
    {
        return new Status(this.configFilePath.toAbsolutePath().toString(), this.loadedAt, this.rejectedAt);
    }

    /**
     * Prepares the generation with every listener before it is published; on a rejection, the
     * listeners that had prepared it retire it again, so nothing built for it outlives it.
     */
    private void prepare(final List<GatewayRoute> routes)
    {
        final List<RouteGenerationListener> prepared = new ArrayList<>();
        try
        {
            for (final RouteGenerationListener listener : this.listeners)
            {
                listener.prepare(routes);
                prepared.add(listener);
            }
        }
        catch (final RuntimeException e)
        {
            for (final RouteGenerationListener listener : prepared)
            {
                retire(listener, routes);
            }
            throw e;
        }
    }

    private void retire(final List<GatewayRoute> routes)
    {
        for (final RouteGenerationListener listener : this.listeners)
        {
            retire(listener, routes);
        }
    }

    /**
     * A failure to release an old generation is not a failure of the new one: by then it is
     * published, and reporting the reload as rejected would claim the old routes still run.
     */
    private static void retire(final RouteGenerationListener listener, final List<GatewayRoute> routes)
    {
        try
        {
            listener.retire(routes);
        }
        catch (final RuntimeException e)
        {
            log.warn("Releasing a route generation failed in {}", listener, e);
        }
    }

    /**
     * Adds the listener and prepares the routes currently in service with it, as one step with
     * respect to reloads. A listener that fails to prepare them is not added.
     */
    public synchronized void onReload(final RouteGenerationListener listener)
    {
        listener.prepare(this.routeRegistry.getRoutes());
        this.listeners.add(listener);
    }
}