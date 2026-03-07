package com.ethlo.venturi.config;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.validation.ValidationResult;

public final class HotReloadService implements Runnable
{
    private static final Logger log = LoggerFactory.getLogger(HotReloadService.class);
    private static final long DEBOUNCE_MILLIS = 1000L;

    private final Path configDirectory;
    private final String configFileName;
    private final ConfigurationManager configManager;
    private final RouteRegistry routeRegistry;

    private long lastReloadTime = 0L;

    public HotReloadService(final Path configFilePath, final ConfigurationManager configManager, final RouteRegistry routeRegistry)
    {
        this.configDirectory = configFilePath.toAbsolutePath().getParent();
        this.configFileName = configFilePath.getFileName().toString();
        this.configManager = configManager;
        this.routeRegistry = routeRegistry;
    }

    @Override
    public void run()
    {
        try (final WatchService watcher = FileSystems.getDefault().newWatchService())
        {
            this.configDirectory.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY);

            log.info("Started watching {} for configuration changes", this.configFileName);

            while (!Thread.currentThread().isInterrupted())
            {
                final WatchKey key = watcher.poll(1, TimeUnit.SECONDS);

                if (key != null)
                {
                    boolean configChanged = false;
                    for (final WatchEvent<?> event : key.pollEvents())
                    {
                        final Path changedFile = (Path) event.context();
                        if (changedFile != null && changedFile.toString().equals(this.configFileName))
                        {
                            configChanged = true;
                        }
                    }

                    if (configChanged)
                    {
                        final long now = System.currentTimeMillis();
                        if (now - this.lastReloadTime > DEBOUNCE_MILLIS)
                        {
                            // Wait briefly to ensure the OS has finished writing the file
                            Thread.sleep(500);
                            this.reloadPipeline();
                            this.lastReloadTime = System.currentTimeMillis();
                        }
                    }

                    key.reset();
                }
            }
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
            log.info("Hot reload service interrupted. Shutting down watcher.");
        }
        catch (final Exception e)
        {
            log.error("Fatal error in hot reload watcher: {}", e.getMessage(), e);
        }
    }

    private void reloadPipeline()
    {
        try
        {
            final RoutesConfig routesConfig = this.configManager.load(this.configDirectory.resolve(this.configFileName), RoutesConfig.class);
            final ValidationResult validationResult = ConfigurationManager.validate(routesConfig);
            if (validationResult.hasErrors())
            {
                log.warn("Hot reload failed validation. Retaining current configuration. Errors: {}", validationResult.getErrors());
                return;
            }

            this.configManager.load(routesConfig, this.routeRegistry);
            log.info("Configuration successfully reloaded {} with version {}", this.configFileName, routesConfig.version());
        }
        catch (final Exception e)
        {
            log.warn("Hot reload failed during deserialization. Retaining current configuration: {}", e.getMessage());
        }
    }
}