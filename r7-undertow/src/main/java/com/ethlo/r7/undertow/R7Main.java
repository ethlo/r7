package com.ethlo.r7.undertow;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Xnio;
import org.xnio.XnioWorker;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.ShardedJournalWriter;
import com.ethlo.r7.api.GatewayErrorHandler;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.config.ConfigurationManager;
import com.ethlo.r7.config.HotReloadService;
import com.ethlo.r7.config.RouteDefinition;
import com.ethlo.r7.config.RouteRegistry;
import com.ethlo.r7.config.RoutesConfig;
import com.ethlo.r7.core.StandardErrorHandler;
import com.ethlo.r7.journal.compression.VlfCompressionEngine;
import com.ethlo.r7.status.FileTelemetryRepository;
import com.ethlo.r7.status.MetricsRegistry;
import com.ethlo.r7.status.SimpleMetricsFactory;
import com.ethlo.r7.status.StatusHandler;
import com.ethlo.r7.status.TelemetryRepository;
import com.ethlo.r7.status.TrafficMetricsHandler;
import com.ethlo.r7.status.VersionProvider;
import com.ethlo.r7.status.dto.ModelMapper;
import com.ethlo.r7.status.dto.RouteMetricsDto;
import com.ethlo.r7.status.dto.TelemetryFlusher;
import com.ethlo.r7.undertow.config.ServerConfig;
import com.ethlo.r7.util.CharSequenceUtil;
import com.ethlo.r7.util.SystemUtil;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.util.constants.MediaTypes;
import com.ethlo.r7.validation.ValidationResult;
import com.ethlo.r7.vlf.DiskSpaceUtils;
import com.ethlo.r7.vlf.VlfJournal;
import com.ethlo.r7.vlf.VlfJournalProvider;
import com.ethlo.r7.vlf.VlfRecoveryManager;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.GracefulShutdownHandler;
import io.undertow.util.Headers;

public final class R7Main
{
    private static final Logger logger = LoggerFactory.getLogger(R7Main.class);
    private static final ByteBuffer OK = ByteBuffer.wrap("OK".getBytes(StandardCharsets.UTF_8));
    private final VlfCompressionEngine compressionEngine;
    private final XnioWorker sharedWorker;
    private final GracefulShutdownHandler gracefulShutdownHandler;

    public R7Main(final Path configFile, final Path serverFile) throws IOException
    {
        final HttpHandler benchMarkHandler = exchange -> {
            exchange.setStatusCode(HttpStatuses.OK);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, MediaTypes.TEXT_PLAIN);
            exchange.getResponseSender().send(OK.duplicate());
        };

        final RouteRegistry routeRegistry = new RouteRegistry();
        final GatewayScheduler scheduler = new GatewayScheduler(2);
        final ConfigurationManager loader = new ConfigurationManager(scheduler);

        final ServerConfig serverConfig = loader.load(serverFile, ServerConfig.class);
        final ValidationResult result = new ValidationResult();
        serverConfig.validate(result);
        result.throwIfInvalid();
        logger.debug("Loaded server config: {}", serverConfig);

        final GatewayErrorHandler errorHandler = new StandardErrorHandler();

        final RoutesConfig routesConfig = loader.load(configFile, RoutesConfig.class);
        loader.load(routesConfig, routeRegistry);

        final HotReloadService hotReloadService = new HotReloadService(scheduler, configFile, loader, routeRegistry);
        logger.info("Watching config file {} for changes", configFile.toAbsolutePath());

        final ServerConfig.StorageConfig storage = serverConfig.storage();
        final Path workDir = Paths.get(storage.workDir());
        Files.createDirectories(workDir);

        logger.info("Work directory is {} with {} free disk space", workDir, DiskSpaceUtils.formatBytes(DiskSpaceUtils.getSafeUsableSpace(workDir)));

        this.compressionEngine = new VlfCompressionEngine(3, 2);
        final List<Path> paths = VlfRecoveryManager.cleanAndRecover(workDir);
        paths.forEach(compressionEngine::submitForCompression);

        final ShardedJournalWriter<VlfJournal> journalWriter = new ShardedJournalWriter<>(storage.shardCount(), shardIdx ->
        {
            final VlfJournalProvider provider = new VlfJournalProvider(workDir, shardIdx, storage.shardSize(), storage.preFault());
            return new VlfJournal(provider, compressionEngine::submitForCompression);
        }
        );

        final TelemetryRepository telemetryRepository = new FileTelemetryRepository(workDir);
        final List<RouteMetricsDto> existingMetrics = telemetryRepository.load();
        final MetricsRegistry metricsRegistry = new MetricsRegistry();
        for (final GatewayRoute route : routeRegistry.getRoutes())
        {
            final RouteDefinition routeDefinition = routesConfig.routes().stream()
                    .filter(r -> CharSequenceUtil.equals(r.id(), route.id()))
                    .findFirst().orElseThrow();

            final Optional<SimpleMetricsFactory.GF> metricsFilter = route.filters().stream()
                    .filter(f -> f instanceof SimpleMetricsFactory.GF)
                    .map(SimpleMetricsFactory.GF.class::cast)
                    .findFirst();

            metricsFilter.ifPresent(gf -> existingMetrics.stream()
                    .filter(routeMetricsDto -> route.id().equals(routeMetricsDto.id()))
                    .findFirst()
                    .ifPresent(gf::set));

            metricsRegistry.register(routeDefinition, metricsFilter.orElse(null));
        }

        final TelemetryFlusher telemetryFlusher = new TelemetryFlusher(telemetryRepository, () -> metricsRegistry.getAll().entrySet()
                .stream()
                .map(ModelMapper::mapToDto)
                .toList()
        );
        telemetryFlusher.start();

        // 1. Create the single shared worker for all Undertow instances
        this.sharedWorker = createSharedWorker(serverConfig);

        final R7UndertowHandler r7UndertowHandler = new R7UndertowHandler(serverConfig, routeRegistry, journalWriter, errorHandler);
        final TrafficMetricsHandler trafficMetricsHandler = new TrafficMetricsHandler(r7UndertowHandler);

        final HttpHandler rootHandler = Handlers.path()
                .addPrefixPath("/", trafficMetricsHandler);

        final Undertow.Builder builder = Undertow.builder();
        builder.setWorker(sharedWorker);
        this.gracefulShutdownHandler = new GracefulShutdownHandler(rootHandler);
        builder.setHandler(gracefulShutdownHandler);
        configureServer(builder, serverConfig);
        final Undertow server = builder.build();

        // Used for having fast internal HTTP endpoint to talk to
        setupTestBackEndForProxy(benchMarkHandler, sharedWorker);

        final StatusHandler statusHandler = new StatusHandler(metricsRegistry, serverConfig, routeRegistry.getRoutes());

        setupStatusBackend(statusHandler, serverConfig.statusPort(), serverConfig.statusHost(), sharedWorker);

        // Explicit Shutdown Hook
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            logger.info("Shutdown signal received. Initiating graceful shutdown sequence...");

            // 1. Drain in-flight requests (Requires Undertow's GracefulShutdownHandler)
            logger.info("Rejecting new requests and draining in-flight traffic...");
            gracefulShutdownHandler.shutdown();
            try
            {
                // Give active requests up to 5 seconds to finish naturally
                gracefulShutdownHandler.awaitShutdown(5000);
            }
            catch (final InterruptedException e)
            {
                logger.warn("Interrupted while waiting for in-flight requests to drain.");
                Thread.currentThread().interrupt();
            }

            // 2. Stop the Undertow listener
            logger.info("Stopping Undertow server...");
            server.stop();

            // Allow XNIO a brief moment to process the connection-closed events
            // before we terminate the underlying thread pool.
            try
            {
                Thread.sleep(500);
            }
            catch (final InterruptedException e)
            {
                logger.warn("Interrupted while waiting for XNIO conduit cleanup.");
                Thread.currentThread().interrupt();
            }

            //shutdownThreadpool();

            // Close auxiliary resources safely
            logger.info("Closing compression engine...");
            this.compressionEngine.close();

            // The journal closes absolutely LAST to ensure all drained requests were logged
            logger.info("Shutting down journal writer...");
            journalWriter.shutdown();

            logger.info("Shutdown sequence complete.");

        }, "r7-shutdown-hook"
        ));

        server.start();

        // Have to happen after start
        statusHandler.setConnectorStatistics(server.getListenerInfo().getFirst().getConnectorStatistics());

        System.gc();
        final R7ConsolePrinter consolePrinter = new VerboseR7ConsolePrinter();
        consolePrinter.printFullReport(serverConfig, routeRegistry.getRoutes());

        final Duration uptime = SystemUtil.getUptime();
        logger.info("🚀 Ethlo R7 Gateway - version {}, started in {}ms, listening at {}", VersionProvider.getVersion(), uptime.toMillis(), server.getListenerInfo().stream().map(Undertow.ListenerInfo::getAddress).toList());
    }

    private void shutdownThreadpool()
    {
        logger.info("Shutting down shared worker...");
        this.sharedWorker.shutdown();

        try
        {
            // wait for it to finish
            if (!this.sharedWorker.awaitTermination(2, TimeUnit.SECONDS))
            {
                logger.warn("Worker did not terminate gracefully in time. Forcing shutdown.");
                this.sharedWorker.shutdownNow();
            }
        }
        catch (final InterruptedException e)
        {
            logger.warn("Interrupted while waiting for worker shutdown.");
            this.sharedWorker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void setupTestBackEndForProxy(final HttpHandler httpHandler, final XnioWorker sharedWorker)
    {
        final Undertow.Builder routerBackendTest = Undertow.builder();
        final HttpHandler targetHandler = Handlers.path()
                .addPrefixPath("/", httpHandler);
        routerBackendTest.setHandler(targetHandler);
        routerBackendTest.setWorker(sharedWorker);
        routerBackendTest.addHttpListener(1111, "0.0.0.0");
        routerBackendTest.build().start();
    }

    // MUST be public
    public static void main(final String[] args) throws IOException
    {
        // Force all silent background thread crashes to print to standard error
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
        {
            System.err.println("FATAL THREAD CRASH [" + t.getName() + "]: " + e.getMessage());
            e.printStackTrace(System.err);
        });

        setupLogging();

        logger.debug("Main class started at {} ms since process start", SystemUtil.getUptime());
        new R7Main(Paths.get("config/routes.yaml"), Paths.get("config/server.yaml"));
    }

    private static void setupLogging()
    {
        final Path configFilePath = Paths.get("config/logback.xml").toAbsolutePath();
        if (!Files.exists(configFilePath))
        {
            System.err.println("No logback.xml file found");
            return;
        }

        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        try
        {
            final JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);

            // This clears all existing configuration
            context.reset();

            // Load the user's XML configuration
            configurator.doConfigure(configFilePath.toFile());

            // Inject the telemetry appender after XML is loaded so it is not reset
            final ParserRejectionAppender appender = new ParserRejectionAppender();
            appender.setContext(context);
            appender.start();
        }
        catch (final JoranException je)
        {
            System.err.println("FATAL: Logback failed to configure from XML: " + je.getMessage());
        } finally
        {
            // This is the magic line that will reveal the GraalVM reflection error
            StatusPrinter.printInCaseOfErrorsOrWarnings(context);
        }
    }

    private XnioWorker createSharedWorker(final ServerConfig config) throws IOException
    {
        final ServerConfig.WorkerConfig worker = config.worker();
        final OptionMap.Builder options = OptionMap.builder()
                .set(Options.WORKER_IO_THREADS, worker.ioThreads())
                .set(Options.WORKER_TASK_CORE_THREADS, worker.taskCoreThreads())
                .set(Options.WORKER_TASK_MAX_THREADS, worker.taskMaxThreads())
                .set(Options.STACK_SIZE, worker.stackSize())
                .set(Options.CONNECTION_HIGH_WATER, worker.connectionHighWater())
                .set(Options.CONNECTION_LOW_WATER, worker.connectionLowWater());

        return Xnio.getInstance().createWorker(options.getMap());
    }

    private void setupStatusBackend(final StatusHandler statusHandler, final int port, final String host, final XnioWorker sharedWorker)
    {
        final Undertow.Builder statusServer = Undertow.builder();
        final HttpHandler targetHandler = Handlers.path()
                .addPrefixPath("/", statusHandler);
        statusServer.setHandler(targetHandler);
        statusServer.setWorker(sharedWorker);
        statusServer.addHttpListener(port, host);
        statusServer.build().start();
    }

    private void configureServer(final Undertow.Builder builder, final ServerConfig config)
    {
        final ServerConfig.SocketConfig socket = config.socket();
        final ServerConfig.OptionsConfig opts = config.options();

        builder.addHttpListener(config.port(), config.host())
                // Socket Layer
                .setSocketOption(Options.TCP_NODELAY, socket.tcpNodelay())
                .setSocketOption(Options.REUSE_ADDRESSES, socket.reuseAddresses())
                .setSocketOption(Options.BACKLOG, socket.backlog())
                .setSocketOption(Options.READ_TIMEOUT, socket.readTimeout())

                // Worker Layer removed here because the shared worker handles it

                // Protocol & Memory
                .setServerOption(UndertowOptions.MAX_HEADER_SIZE, opts.maxHeaderSize())
                .setServerOption(UndertowOptions.REQUEST_PARSE_TIMEOUT, opts.requestParseTimeout())
                .setServerOption(UndertowOptions.MAX_HEADERS, opts.maxHeaderCount())
                .setServerOption(UndertowOptions.ENABLE_STATISTICS, true)

                .setServerOption(UndertowOptions.RECORD_REQUEST_START_TIME, true)

                .setServerOption(UndertowOptions.ENABLE_HTTP2, opts.enableHttp2())
                .setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE, opts.alwaysSetKeepAlive())
                .setBufferSize(opts.bufferSize())
                .setDirectBuffers(opts.directBuffers());
    }
}