package com.ethlo.r7.server.blocking;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.ShardedJournalWriter;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.config.ConfigurationManager;
import com.ethlo.r7.config.DefaultGatewayRoute;
import com.ethlo.r7.config.HotReloadService;
import com.ethlo.r7.config.RouteGenerationListener;
import com.ethlo.r7.config.RouteRegistry;
import com.ethlo.r7.core.StandardErrorHandler;
import com.ethlo.r7.r7f.JournalFiles;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.r7f.R7fJournalProvider;
import com.ethlo.r7.r7f.R7fRecoveryManager;
import com.ethlo.r7.server.GatewayPipeline;
import com.ethlo.r7.server.config.ServerConfig;
import com.ethlo.r7.spi.EngineContext;
import com.ethlo.r7.status.FileTelemetryRepository;
import com.ethlo.r7.status.ManagementEndpoint;
import com.ethlo.r7.status.MetricsRegistry;
import com.ethlo.r7.upstream.HttpUpstream;
import com.ethlo.r7.upstream.UpstreamOptions;
import com.ethlo.r7.validation.ValidationResult;

/**
 * r7 without an HTTP server: the configuration, route registry with hot reload, journal and
 * pipeline, wired to {@link HttpUpstream}. A thread-per-request server creates one, hands every
 * request to {@link #handle}, and {@link #close}s it on shutdown.
 * <p>
 * The data-plane host and port in the server configuration are the server's to use or ignore:
 * a servlet container listens where it was told to.
 */
public final class BlockingGateway implements AutoCloseable
{
    private final ServerConfig serverConfig;
    private final GatewayPipeline pipeline;
    private final ShardedJournalWriter<R7fJournal> journalWriter;
    private final GatewayScheduler scheduler;
    private final ManagementEndpoint managementEndpoint;
    private final AtomicBoolean closed = new AtomicBoolean();

    public BlockingGateway(final Path routesFile, final Path serverFile) throws IOException
    {
        final RouteRegistry routeRegistry = new RouteRegistry();
        this.scheduler = new GatewayScheduler(5);
        this.serverConfig = loadServerSettings(serverFile);

        final ServerConfig.StorageConfig storage = this.serverConfig.storage();
        final Path workDir = Paths.get(storage.workDir());
        JournalFiles.createDirectories(workDir);
        R7fRecoveryManager.cleanAndRecover(workDir);
        this.journalWriter = new ShardedJournalWriter<>(storage.shardCount(), shardIdx ->
                new R7fJournal(new R7fJournalProvider(workDir, shardIdx, storage.shardSize().bytes(), storage.preFault())));

        final MetricsRegistry metricsRegistry = new MetricsRegistry(new FileTelemetryRepository(workDir), this.scheduler);
        final ConfigurationManager configurationManager = new ConfigurationManager(new EngineContext(Map.of(
                GatewayScheduler.class, this.scheduler,
                MetricsRegistry.class, metricsRegistry)));
        final HotReloadService hotReloadService = new HotReloadService(this.scheduler, routesFile, configurationManager, routeRegistry);

        this.pipeline = new GatewayPipeline(this.serverConfig, routeRegistry, this.journalWriter, new StandardErrorHandler(), this.scheduler, this::connect);
        final String serverConfigFile = Files.exists(serverFile) ? serverFile.toAbsolutePath().toString() : null;
        // No listener counters of our own; the server may know them, but none is asked yet.
        this.managementEndpoint = new ManagementEndpoint(metricsRegistry, this.serverConfig, serverConfigFile, routeRegistry, hotReloadService, this.pipeline, null);
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
    }

    /**
     * The configuration files as the environment names them, the way every r7 server reads them.
     */
    public static BlockingGateway fromEnvironment() throws IOException
    {
        final String routesPath = System.getenv().getOrDefault("R7_ROUTES_CONFIG", "config/routes.yaml");
        final String serverPath = System.getenv().getOrDefault("R7_SERVER_CONFIG", "config/server.yaml");
        return new BlockingGateway(Paths.get(routesPath), Paths.get(serverPath));
    }

    public ServerConfig serverConfig()
    {
        return this.serverConfig;
    }

    public GatewayPipeline pipeline()
    {
        return this.pipeline;
    }

    /**
     * The management port's dashboard and JSON, for a server that runs one.
     */
    public ManagementEndpoint managementEndpoint()
    {
        return this.managementEndpoint;
    }

    /**
     * Runs one request through the pipeline on the calling thread, and the completion work
     * (journal, completed filters) once the response has been sent.
     */
    public void handle(final BlockingServerExchange exchange)
    {
        try
        {
            this.pipeline.handle(exchange);
        }
        finally
        {
            exchange.complete();
        }
        if (exchange.isAborted())
        {
            // A server finishes a response its handler returns from normally - for a chunked
            // one, by writing the last chunk - so a response abandoned part-way must end in a
            // throw. Tomcat and Níma both drop the connection when a handler throws after the
            // response was committed, which is the only signal of truncation a client can see.
            throw new ResponseAbortedException();
        }
    }

    /**
     * Thrown out of the server's handler to make it drop the client connection.
     */
    public static final class ResponseAbortedException extends RuntimeException
    {
        ResponseAbortedException()
        {
            super("The upstream response failed part-way; dropping the client connection", null, false, false);
        }
    }

    private HttpUpstream connect(final DefaultGatewayRoute route)
    {
        return new HttpUpstream(UpstreamOptions.of(this.serverConfig, route.routeDefinition().upstream()));
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

    @Override
    public void close()
    {
        if (!this.closed.compareAndSet(false, true))
        {
            return;
        }
        this.journalWriter.shutdown();
        this.scheduler.shutdown();
    }
}
