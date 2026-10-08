package com.ethlo.r7.server.blocking;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.ShardedJournalWriter;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.config.ConfigurationException;
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
import com.ethlo.r7.util.Fingerprint;
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
    static final String ROUTES_ENVIRONMENT_VARIABLE = "R7_ROUTES_CONFIG";
    static final String SERVER_ENVIRONMENT_VARIABLE = "R7_SERVER_CONFIG";
    static final String DEFAULT_ROUTES_FILE = "routes.yaml";
    static final String DEFAULT_SERVER_FILE = "server.yaml";

    private static final Logger logger = LoggerFactory.getLogger(BlockingGateway.class);

    private final ServerConfig serverConfig;
    private final GatewayPipeline pipeline;
    private final ShardedJournalWriter<R7fJournal> journalWriter;
    private final GatewayScheduler scheduler;
    private final MetricsRegistry metricsRegistry;
    private final ManagementEndpoint managementEndpoint;
    private final ListenerStatistics statistics = new ListenerStatistics();
    private final long maxHeadBytes;
    private final int maxHeaderCount;
    private final long maxEntityBytes;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * @param routesFile the routes, which must exist: a gateway with nothing to route is a mistake
     * @param serverFile the server settings, or a path with no file at it to run on the built-in
     *                   defaults
     */
    public BlockingGateway(final Path routesFile, final Path serverFile) throws IOException
    {
        // Before anything is created on disk, so a mistyped path leaves no journal directory behind.
        requireRoutesFile(routesFile);
        final RouteRegistry routeRegistry = new RouteRegistry();
        this.scheduler = new GatewayScheduler(5);
        FutureTask<ShardedJournalWriter<R7fJournal>> journalOpening = null;
        try
        {
            this.serverConfig = loadServerSettings(serverFile);
            final ServerConfig.LimitsConfig limits = this.serverConfig.limits();
            this.maxHeadBytes = limits.maxHeaderSize().bytes();
            this.maxHeaderCount = limits.maxHeaderCount();
            this.maxEntityBytes = limits.maxEntitySize().bytes();

            final ServerConfig.StorageConfig storage = this.serverConfig.storage();
            final Path workDir = Paths.get(storage.workDir());
            JournalFiles.createDirectories(workDir);
            R7fRecoveryManager.cleanAndRecover(workDir);
            // Opened while the routes load, not before: opening proves zstd works (loading its native
            // library and binding it through FFM), which takes about as long as loading the routes.
            journalOpening = new FutureTask<>(() -> new ShardedJournalWriter<>(storage.shardCount(), shardIdx ->
                    new R7fJournal(new R7fJournalProvider(workDir, shardIdx, storage.shardSize().bytes(), storage.preFault(), storage.journalCompressionLevel()))));
            Thread.ofPlatform().daemon().name("r7-journal-open").start(journalOpening);

            this.metricsRegistry = new MetricsRegistry(new FileTelemetryRepository(workDir), this.scheduler);
            final ConfigurationManager configurationManager = new ConfigurationManager(new EngineContext(Map.of(
                    GatewayScheduler.class, this.scheduler,
                    MetricsRegistry.class, this.metricsRegistry,
                    Fingerprint.class, Fingerprint.of(storage.journalSecurity().fingerprintKey()))));
            final HotReloadService hotReloadService = new HotReloadService(this.scheduler, routesFile, configurationManager, routeRegistry);
            this.journalWriter = opened(journalOpening);

            this.pipeline = new GatewayPipeline(this.serverConfig, routeRegistry, this.journalWriter, new StandardErrorHandler(), this.scheduler, this::connect);
            final String serverConfigFile = Files.exists(serverFile) ? serverFile.toAbsolutePath().toString() : null;
            this.managementEndpoint = new ManagementEndpoint(this.metricsRegistry, this.serverConfig, serverConfigFile, routeRegistry, hotReloadService, this.pipeline, this.statistics::snapshot, this.scheduler);
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
        catch (final IOException | RuntimeException | Error e)
        {
            // Nothing of a gateway that failed to start may keep running: a servlet container
            // retries a failed deploy in the same JVM.
            abandon(journalOpening);
            this.scheduler.shutdown();
            throw e;
        }
    }

    /**
     * Shuts down the journal of a gateway that failed to start, once it has finished opening.
     */
    private static void abandon(final FutureTask<ShardedJournalWriter<R7fJournal>> journalOpening)
    {
        if (journalOpening == null)
        {
            return;
        }
        try
        {
            journalOpening.get().shutdown();
        }
        catch (final ExecutionException e)
        {
            // It never opened, so there is nothing to shut down
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private static <T> T opened(final FutureTask<T> opening) throws IOException
    {
        try
        {
            return opening.get();
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while opening the journal", e);
        }
        catch (final ExecutionException e)
        {
            switch (e.getCause())
            {
                case IOException io -> throw io;
                case RuntimeException runtime -> throw runtime;
                case Error error -> throw error;
                default -> throw new IOException("Could not open the journal", e.getCause());
            }
        }
    }

    /**
     * The configuration files as the environment names them, the way every r7 server reads them.
     */
    public static BlockingGateway fromEnvironment() throws IOException
    {
        final Map<String, String> environment = System.getenv();
        final Path routesFile = Paths.get(environment.getOrDefault(ROUTES_ENVIRONMENT_VARIABLE, DEFAULT_ROUTES_FILE));
        return new BlockingGateway(routesFile, serverFile(environment.get(SERVER_ENVIRONMENT_VARIABLE), SERVER_ENVIRONMENT_VARIABLE));
    }

    /**
     * The server settings to load: {@code server.yaml} in the working directory when nothing names one, and it may
     * be absent. A file that is named, by {@code source}, must exist: falling back to the defaults
     * when an operator mistyped the path would quietly drop their limits and trusted proxies.
     *
     * @param configured the path as configured, or null when nothing names one
     * @param source     what named it, for the error
     */
    public static Path serverFile(final String configured, final String source)
    {
        if (configured == null || configured.isBlank())
        {
            return Paths.get(DEFAULT_SERVER_FILE);
        }
        final Path path = Paths.get(configured.strip());
        if (!Files.isRegularFile(path))
        {
            throw new ConfigurationException(source + " names " + path.toAbsolutePath() + ", which is not a file. "
                    + "Create it, or leave " + source + " unset to run on the built-in server defaults.");
        }
        return path;
    }

    private static void requireRoutesFile(final Path routesFile)
    {
        if (!Files.isRegularFile(routesFile))
        {
            throw new ConfigurationException("No routes file at " + routesFile.toAbsolutePath() + ". "
                    + "r7 needs a routes.yaml: create one there, or set " + ROUTES_ENVIRONMENT_VARIABLE + " to where yours is.");
        }
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
     * The data-plane listener's counters. A server that can see its connections reports them here.
     */
    public ListenerStatistics statistics()
    {
        return this.statistics;
    }

    /**
     * Runs one request through the pipeline on the calling thread, and the completion work
     * (journal, completed filters) once the response has been sent.
     */
    public void handle(final BlockingServerExchange exchange)
    {
        this.statistics.requestStarted();
        try
        {
            if (withinLimits(exchange))
            {
                this.pipeline.handle(exchange);
            }
        }
        finally
        {
            try
            {
                exchange.complete();
            }
            finally
            {
                this.statistics.requestFinished(System.nanoTime() - exchange.requestStartNanos(), exchange.responseStatus(),
                        exchange.totalResponseBytes(), exchange.totalRequestBytes());
            }
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
     * {@code limits.*}, checked here so that they hold on every server whatever its own parser
     * allows: Helidon has no header count limit, and a servlet container has its own settings,
     * which the operator may not have aligned. A server that enforces a limit in its parser just
     * never lets such a request get this far. The head is measured as sent, request line
     * included.
     */
    private boolean withinLimits(final BlockingServerExchange exchange)
    {
        if (exchange.requestHeaderBytes() > this.maxHeadBytes || exchange.requestHeaderCount() > this.maxHeaderCount)
        {
            exchange.refuse(431, "Request Header Fields Too Large");
            return false;
        }
        final String contentLength = exchange.requestHeaders().getFirst("Content-Length");
        if (contentLength != null && exceeds(contentLength, this.maxEntityBytes))
        {
            // Refused before a byte of it is read or forwarded; a chunked body is held to the same
            // limit as it streams (limitRequestBody), and answered 413 when it passes it.
            exchange.refuse(413, "Request Entity Too Large");
            return false;
        }
        exchange.limitRequestBody(this.maxEntityBytes);
        return true;
    }

    /**
     * Whether a Content-Length value is larger than {@code max}. One that is not a number is not
     * this check's to reject: the request guards refuse it with 400.
     */
    private static boolean exceeds(final String contentLength, final long max)
    {
        try
        {
            return Long.parseLong(contentLength.trim()) > max;
        }
        catch (final NumberFormatException e)
        {
            return false;
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
        ServerConfig serverConfig;
        if (!Files.exists(serverFile))
        {
            serverConfig = ServerConfig.standard();
            logger.info("No {}, running on the built-in server defaults: gateway on {}:{}, management on {}:{}, journals in {}",
                    serverFile, serverConfig.server().host(), serverConfig.server().port(), serverConfig.management().host(),
                    serverConfig.management().port(), Paths.get(serverConfig.storage().workDir()).toAbsolutePath());
        }
        else
        {
            serverConfig = ConfigurationManager.load(serverFile, ServerConfig.class);
            if (serverConfig == null)
            {
                serverConfig = ServerConfig.standard();
            }
        }
        // The defaults too: they take the fingerprint key from the environment, and a missing one
        // must be the error naming it, before any journal directory is touched.
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
        this.managementEndpoint.close();
        this.journalWriter.shutdown();
        this.metricsRegistry.close();
        this.scheduler.shutdown();
    }
}
