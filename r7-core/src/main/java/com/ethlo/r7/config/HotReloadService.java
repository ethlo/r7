package com.ethlo.r7.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

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
    private long lastKnownModified;

    public HotReloadService(final GatewayScheduler scheduler, final Path configFilePath, final ConfigurationManager configManager, final RouteRegistry routeRegistry)
    {
        scheduler.scheduleEvery(Duration.ofSeconds(1), this::pollForChanges);
        this.configFilePath = configFilePath;
        this.configManager = configManager;
        this.routeRegistry = routeRegistry;
        this.lastKnownModified = System.currentTimeMillis();
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
            reloadPipeline();
        }
    }

    private void reloadPipeline()
    {
        try
        {
            final RoutesConfig routesConfig = this.configManager.load(this.configFilePath, RoutesConfig.class);
            final ValidationResult validationResult = ConfigurationManager.validate(routesConfig);
            if (validationResult.hasErrors())
            {
                log.warn("Hot reload failed validation. Retaining current configuration. Errors: {}", validationResult.getErrors());
                return;
            }

            this.configManager.load(routesConfig, this.routeRegistry);
            log.info("Configuration successfully reloaded {} with version {}", this.configFilePath, routesConfig.version());
        }
        catch (final Exception e)
        {
            log.warn("Hot reload failed during deserialization. Retaining current configuration: {}", e.getMessage());
        }
    }
}