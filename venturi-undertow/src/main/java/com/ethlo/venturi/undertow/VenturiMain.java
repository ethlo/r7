package com.ethlo.venturi.undertow;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.Options;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import com.ethlo.venturi.ShardedJournalWriter;
import com.ethlo.venturi.api.GatewayErrorHandler;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.config.RouteDefinition;
import com.ethlo.venturi.config.RouteRegistry;
import com.ethlo.venturi.config.RoutesConfig;
import com.ethlo.venturi.config.VenturiLoader;
import com.ethlo.venturi.core.StandardErrorHandler;
import com.ethlo.venturi.journal.compression.VlfCompressionEngine;
import com.ethlo.venturi.status.MetricsRegistry;
import com.ethlo.venturi.status.SimpleMetricsFactory;
import com.ethlo.venturi.status.StatusHandler;
import com.ethlo.venturi.status.TrafficMetricsHandler;
import com.ethlo.venturi.status.VersionProvider;
import com.ethlo.venturi.undertow.config.ServerConfig;
import com.ethlo.venturi.util.CharSequenceUtil;
import com.ethlo.venturi.util.SystemUtil;
import com.ethlo.venturi.util.constants.HttpStatuses;
import com.ethlo.venturi.util.constants.MediaTypes;
import com.ethlo.venturi.validation.ValidationResult;
import com.ethlo.venturi.vlf.DiskSpaceUtils;
import com.ethlo.venturi.vlf.VlfJournal;
import com.ethlo.venturi.vlf.VlfJournalProvider;
import com.ethlo.venturi.vlf.VlfRecoveryManager;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.util.Headers;

public final class VenturiMain
{
    public static final int JOURNAL_SHARD_COUNT = 4;
    public static final int JOURNAL_SHARD_SIZE_BYTES = 1024 * 1024 * 100;
    private static final Logger logger = LoggerFactory.getLogger(VenturiMain.class);
    private static final ByteBuffer OK = ByteBuffer.wrap("OK".getBytes(StandardCharsets.UTF_8));


    public VenturiMain(Path configFile, Path serverFile) throws IOException
    {
        final HttpHandler benchMarkHandler = exchange -> {
            exchange.setStatusCode(HttpStatuses.OK);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, MediaTypes.TEXT_PLAIN);
            exchange.getResponseSender().send(OK.duplicate());
        };

        final RouteRegistry routeRegistry = new RouteRegistry();
        final VenturiLoader loader = new VenturiLoader();

        final ServerConfig serverConfig = loader.load(serverFile, ServerConfig.class);
        final ValidationResult result = new ValidationResult();
        serverConfig.validate(result);
        result.throwIfInvalid();
        logger.debug("Loaded server config: {}", serverConfig);

        final GatewayErrorHandler errorHandler = new StandardErrorHandler();

        final RoutesConfig routesConfig = loader.load(configFile, RoutesConfig.class);
        loader.load(routesConfig, routeRegistry);

        final VenturiConsolePrinter consolePrinter = new VerboseVenturiConsolePrinter();
        consolePrinter.printFullReport(serverConfig, routeRegistry.getRoutes());

        final ServerConfig.StorageConfig storage = serverConfig.storage();
        final Path workDir = Paths.get(storage.workDir());
        Files.createDirectories(workDir);

        logger.info("Work directory is {} with {} free disk space", workDir, DiskSpaceUtils.formatBytes(DiskSpaceUtils.getSafeUsableSpace(workDir)));

        final VlfCompressionEngine compressionEngine = new VlfCompressionEngine(3, 2);
        final List<Path> paths = VlfRecoveryManager.cleanAndRecover(workDir);
        paths.forEach(compressionEngine::submitForCompression);

        final ShardedJournalWriter<VlfJournal> gatewayExchangeDataWriter = new ShardedJournalWriter<>(JOURNAL_SHARD_COUNT, shardIdx ->
        {
            final VlfJournalProvider provider = new VlfJournalProvider(workDir, shardIdx, JOURNAL_SHARD_SIZE_BYTES);
            return new VlfJournal(provider, compressionEngine::submitForCompression);
        }
        );

        final MetricsRegistry metricsRegistry = new MetricsRegistry();
        for (GatewayRoute route : routeRegistry.getRoutes())
        {
            final RouteDefinition routeDefinition = routesConfig.routes.stream()
                    .filter(r -> CharSequenceUtil.equals(r.id(), route.id()))
                    .findFirst().orElseThrow();

            for (GatewayFilter filter : route.filters())
            {
                if (filter instanceof SimpleMetricsFactory.GF metricsFilter)
                {
                    metricsRegistry.register(routeDefinition, metricsFilter);
                }
            }
        }
        final StatusHandler statusHandler = new StatusHandler(metricsRegistry, serverConfig);

        final VenturiUndertowHandler venturiUndertowHandler = new VenturiUndertowHandler(serverConfig, routeRegistry, gatewayExchangeDataWriter, errorHandler);
        final TrafficMetricsHandler trafficMetricsHandler = new TrafficMetricsHandler(venturiUndertowHandler);

        final HttpHandler rootHandler = Handlers.path()
                .addExactPath("/status", statusHandler)
                .addExactPath("/benchmark", benchMarkHandler)
                .addPrefixPath("/", trafficMetricsHandler);

        final Undertow.Builder builder = Undertow.builder();
        builder.setHandler(rootHandler);
        configureServer(builder, serverConfig);
        final Undertow server = builder.build();

        // Used for having fast internal HTTP endpoint to talk to
        setupTestBackEndForProxy(benchMarkHandler);

        // Explicit Shutdown Hook
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            logger.info("Shutdown signal received.");
            gatewayExchangeDataWriter.shutdown();
            compressionEngine.close();
            server.stop();
        }, "shutdown-hook"
        ));

        server.start();

        final Duration uptime = SystemUtil.getUptime();
        logger.info("🚀 Started ethlo R7 version {} in {}ms, listening at {}", VersionProvider.getVersion(), uptime.toMillis(), server.getListenerInfo().stream().map(Undertow.ListenerInfo::getAddress).toList());
    }

    private static void setupTestBackEndForProxy(HttpHandler httpHandler)
    {
        final Undertow.Builder target = Undertow.builder();
        final HttpHandler targetHandler = Handlers.path()
                .addPrefixPath("/", httpHandler);
        target.setHandler(targetHandler);
        target.addHttpListener(1111, "0.0.0.0");
        target.build().start();
    }

    // MUST be public
    public static void main(String[] args) throws IOException
    {
        setupLogging();
        logger.debug("Main class started at {} ms since process start", SystemUtil.getUptime());
        new VenturiMain(Paths.get("routes.yaml"), Paths.get("server.yaml"));
    }

    private static void setupLogging()
    {
        final Path configFilePath = Paths.get("logback.xml").toAbsolutePath();
        if (!Files.exists(configFilePath))
        {
            logger.info("No logback.xml file found");
            return;
        }
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        try
        {
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            context.reset();
            configurator.doConfigure(configFilePath.toFile());
        }
        catch (JoranException je)
        {
            // StatusPrinter will handle the error message
        }
    }

    private void configureServer(Undertow.Builder builder, ServerConfig config)
    {
        ServerConfig.WorkerConfig worker = config.worker();
        ServerConfig.SocketConfig socket = config.socket();
        ServerConfig.OptionsConfig opts = config.options();

        builder.addHttpListener(config.port(), config.host())
                // Socket Layer
                .setSocketOption(Options.TCP_NODELAY, socket.tcpNodelay())
                .setSocketOption(Options.REUSE_ADDRESSES, socket.reuseAddresses())
                .setSocketOption(Options.BACKLOG, socket.backlog())
                .setSocketOption(Options.READ_TIMEOUT, socket.readTimeout())

                // Worker Layer
                .setWorkerOption(Options.WORKER_IO_THREADS, worker.ioThreads())
                .setWorkerOption(Options.WORKER_TASK_CORE_THREADS, worker.taskCoreThreads())
                .setWorkerOption(Options.WORKER_TASK_MAX_THREADS, worker.taskMaxThreads())
                .setWorkerOption(Options.STACK_SIZE, worker.stackSize())
                .setWorkerOption(Options.CONNECTION_HIGH_WATER, worker.connectionHighWater())
                .setWorkerOption(Options.CONNECTION_LOW_WATER, worker.connectionLowWater())

                // Protocol & Memory
                .setServerOption(UndertowOptions.MAX_HEADER_SIZE, opts.maxHeaderSize())
                .setServerOption(UndertowOptions.REQUEST_PARSE_TIMEOUT, opts.requestParseTimeout())
                .setServerOption(UndertowOptions.MAX_HEADERS, opts.maxHeaderCount())

                .setServerOption(UndertowOptions.RECORD_REQUEST_START_TIME, true)

                .setServerOption(UndertowOptions.ENABLE_HTTP2, opts.enableHttp2())
                .setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE, opts.alwaysSetKeepAlive())
                .setBufferSize(opts.bufferSize())
                .setDirectBuffers(opts.directBuffers());
    }
}