package com.ethlo.r7.helidon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.ShardedJournalWriter;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.config.ConfigurationManager;
import com.ethlo.r7.config.DefaultGatewayRoute;
import com.ethlo.r7.config.HotReloadService;
import com.ethlo.r7.config.RouteGenerationListener;
import com.ethlo.r7.config.RouteRegistry;
import com.ethlo.r7.config.TimeoutConfig;
import com.ethlo.r7.core.StandardErrorHandler;
import com.ethlo.r7.r7f.JournalFiles;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.r7f.R7fJournalProvider;
import com.ethlo.r7.r7f.R7fRecoveryManager;
import com.ethlo.r7.server.GatewayPipeline;
import com.ethlo.r7.server.config.ServerConfig;
import com.ethlo.r7.spi.EngineContext;
import com.ethlo.r7.validation.ValidationResult;
import io.helidon.webserver.WebServer;

/**
 * EXPERIMENTAL: r7 on Helidon's Níma web server. The same configuration files, routes, filters,
 * journal and pipeline as the Undertow build; a different HTTP server and upstream client
 * underneath. Not yet included: the management port and dashboard, static content, WebSocket
 * proxying, and https upstreams (design/server-spi.md, step 6).
 */
public final class R7Helidon
{
    private static final Logger logger = LoggerFactory.getLogger(R7Helidon.class);

    private final WebServer server;
    private final ShardedJournalWriter<R7fJournal> journalWriter;
    private final GatewayScheduler scheduler;
    private final AtomicBoolean stopped = new AtomicBoolean();

    public R7Helidon(final Path routesFile, final Path serverFile) throws IOException
    {
        final RouteRegistry routeRegistry = new RouteRegistry();
        this.scheduler = new GatewayScheduler(5);
        final ServerConfig serverConfig = loadServerSettings(serverFile);

        final ServerConfig.StorageConfig storage = serverConfig.storage();
        final Path workDir = Paths.get(storage.workDir());
        JournalFiles.createDirectories(workDir);
        R7fRecoveryManager.cleanAndRecover(workDir);
        this.journalWriter = new ShardedJournalWriter<>(storage.shardCount(), shardIdx ->
                new R7fJournal(new R7fJournalProvider(workDir, shardIdx, storage.shardSize().bytes(), storage.preFault())));

        final ConfigurationManager configurationManager = new ConfigurationManager(new EngineContext(Map.of(GatewayScheduler.class, this.scheduler)));
        final HotReloadService hotReloadService = new HotReloadService(this.scheduler, routesFile, configurationManager, routeRegistry);

        final GatewayPipeline pipeline = new GatewayPipeline(serverConfig, routeRegistry, this.journalWriter, new StandardErrorHandler(), this.scheduler, R7Helidon::connect);
        hotReloadService.onReload(new RouteGenerationListener()
        {
            @Override
            public void prepare(final List<GatewayRoute> routes)
            {
                pipeline.prepare(routes);
            }

            @Override
            public void retire(final List<GatewayRoute> routes)
            {
                pipeline.retire(routes);
            }
        });

        final ServerConfig.ServerCoreConfig core = serverConfig.server();
        this.server = WebServer.builder()
                .host(core.host())
                .port(core.port())
                .routing(routing -> routing.any((req, res) ->
                {
                    final HelidonGatewayExchange exchange = new HelidonGatewayExchange(pipeline, req, res);
                    try
                    {
                        pipeline.handle(exchange);
                    }
                    finally
                    {
                        exchange.complete();
                    }
                }))
                .build()
                .start();
        logger.info("ethlo r7 (Helidon Níma, experimental) listening on {}:{}", core.host(), this.server.port());
    }

    private static HttpUpstream connect(final DefaultGatewayRoute route)
    {
        final TimeoutConfig timeouts = Optional.ofNullable(route.routeDefinition().upstream().timeouts()).orElse(new TimeoutConfig(null));
        return new HttpUpstream(Math.toIntExact(timeouts.read().toMillis()));
    }

    private static ServerConfig loadServerSettings(final Path serverFile)
    {
        if (!Files.exists(serverFile))
        {
            return ServerConfig.standard();
        }
        ServerConfig serverConfig = ConfigurationManager.load(serverFile, ServerConfig.class);
        if (serverConfig == null)
        {
            serverConfig = ServerConfig.standard();
        }
        final ValidationResult result = new ValidationResult();
        serverConfig.validate(result);
        result.throwIfInvalid();
        return serverConfig;
    }

    public int port()
    {
        return this.server.port();
    }

    public void stop()
    {
        if (!this.stopped.compareAndSet(false, true))
        {
            return;
        }
        this.server.stop();
        this.journalWriter.shutdown();
        this.scheduler.shutdown();
    }

    public static void main(final String[] args) throws IOException
    {
        final String routesPath = System.getenv().getOrDefault("R7_ROUTES_CONFIG", "config/routes.yaml");
        final String serverPath = System.getenv().getOrDefault("R7_SERVER_CONFIG", "config/server.yaml");
        final R7Helidon gateway = new R7Helidon(Paths.get(routesPath), Paths.get(serverPath));
        Runtime.getRuntime().addShutdownHook(new Thread(gateway::stop, "r7-shutdown-hook"));
    }
}
