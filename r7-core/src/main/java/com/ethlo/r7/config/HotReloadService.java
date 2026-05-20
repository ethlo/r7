package com.ethlo.r7.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.validation.ValidationResult;

public final class HotReloadService
{
    private static final Logger log = LoggerFactory.getLogger(HotReloadService.class);

    private final Path configFilePath;
    private final ConfigurationManager configManager;
    private final RouteRegistry routeRegistry;
    private final Set<Runnable> listeners = new LinkedHashSet<>();
    private long lastKnownModified;

    public HotReloadService(final GatewayScheduler scheduler, final Path configFilePath, final ConfigurationManager configManager, final RouteRegistry routeRegistry)
    {
        scheduler.scheduleEvery(Duration.ofSeconds(1), this::pollForChanges);
        this.configFilePath = configFilePath;
        this.configManager = configManager;
        this.routeRegistry = routeRegistry;
        this.lastKnownModified = System.currentTimeMillis();

        reloadPipeline(true);
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

    private void reloadPipeline(boolean initial)
    {
        try
        {
            log.info("Loading routes settings from {} (hot-reload supported)", configFilePath.toAbsolutePath());
            RoutesDefinition routesConfig = ConfigurationManager.load(this.configFilePath, RoutesDefinition.class);

            if (routesConfig == null)
            {
                log.warn("No settings found in routes.yaml");
                routesConfig = new RoutesDefinition(null, List.of(), List.of());
            }

            final ValidationResult validationResult = ConfigurationManager.validate(routesConfig);
            if (validationResult.hasErrors())
            {
                throw new ConfigurationException("routes.yaml validation failed. Errors: " + String.join(", ", validationResult.getErrors()));
            }

            this.configManager.load(routesConfig, this.routeRegistry);
            listeners.forEach(Runnable::run);
            log.info("Configuration {} successfully loaded {} routes from version {}", this.configFilePath, routesConfig.routes().size(), routesConfig.version());
        }
        catch (final RuntimeException e)
        {
            if (!initial)
            {
                log.warn("Hot reload failed. Retaining current configuration: {}", e.getMessage());
            }
            else
            {
                throw e;
            }
        }
    }

    public void onReload(Runnable onReload)
    {
        this.listeners.add(onReload);
    }
}