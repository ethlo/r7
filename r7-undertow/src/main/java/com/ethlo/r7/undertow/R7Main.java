package com.ethlo.r7.undertow;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Xnio;
import org.xnio.XnioWorker;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter2;
import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.ShardedJournalWriter;
import com.ethlo.r7.api.GatewayErrorHandler;
import com.ethlo.r7.config.ConfigurationException;
import com.ethlo.r7.config.ConfigurationManager;
import com.ethlo.r7.config.HotReloadService;
import com.ethlo.r7.config.RouteRegistry;
import com.ethlo.r7.core.StandardErrorHandler;
import com.ethlo.r7.journal.compression.VlfCompressionEngine;
import com.ethlo.r7.spi.EngineContext;
import com.ethlo.r7.status.FileTelemetryRepository;
import com.ethlo.r7.status.MetricsRegistry;
import com.ethlo.r7.status.StatusHandler;
import com.ethlo.r7.status.TelemetryRepository;
import com.ethlo.r7.status.TrafficMetricsHandler;
import com.ethlo.r7.status.VersionProvider;
import com.ethlo.r7.undertow.config.ServerConfig;
import com.ethlo.r7.util.SystemUtil;
import com.ethlo.r7.validation.ValidationResult;
import com.ethlo.r7.vlf.DiskSpaceUtils;
import com.ethlo.r7.vlf.R7fJournal;
import com.ethlo.r7.vlf.VlfJournalProvider;
import com.ethlo.r7.vlf.VlfRecoveryManager;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.GracefulShutdownHandler;

public final class R7Main
{
    private static final Logger logger = LoggerFactory.getLogger(R7Main.class);
    private final VlfCompressionEngine compressionEngine;
    private final GracefulShutdownHandler gracefulShutdownHandler;

    public R7Main(final Path configFile, final Path serverFile) throws IOException
    {
        final RouteRegistry routeRegistry = new RouteRegistry();
        final GatewayScheduler scheduler = new GatewayScheduler(5);

        final ServerConfig serverConfig = loadServerSettings(serverFile);
        logger.debug("Loaded server config: {}", serverConfig);

        final GatewayErrorHandler errorHandler = new StandardErrorHandler();

        final ServerConfig.StorageConfig storage = serverConfig.storage();
        final Path workDir = Paths.get(storage.workDir());
        Files.createDirectories(workDir);

        final MetricsRegistry metricsRegistry = setupMetricsRegistry(workDir, scheduler);
        final EngineContext engineContext = new EngineContext(Map.of(
                GatewayScheduler.class, scheduler,
                MetricsRegistry.class, metricsRegistry
        ));
        final ConfigurationManager configurationManager = new ConfigurationManager(engineContext);

        final HotReloadService hotReloadService = new HotReloadService(scheduler, configFile, configurationManager, routeRegistry);

        logger.info("Work directory is {} with {} free disk space", workDir, DiskSpaceUtils.formatBytes(DiskSpaceUtils.getSafeUsableSpace(workDir)));

        this.compressionEngine = new VlfCompressionEngine(3, 2);
        final List<Path> paths = VlfRecoveryManager.cleanAndRecover(workDir);
        paths.forEach(compressionEngine::submitForCompression);

        final ShardedJournalWriter<R7fJournal> journalWriter = new ShardedJournalWriter<>(storage.shardCount(), shardIdx ->
        {
            final VlfJournalProvider provider = new VlfJournalProvider(workDir, shardIdx, storage.shardSize().toBytes(), storage.preFault());
            return new R7fJournal(provider, compressionEngine::submitForCompression);
        }
        );

        // Use single shared worker for all Undertow instances
        final XnioWorker sharedWorker = createSharedWorker(serverConfig);

        final R7UndertowHandler r7UndertowHandler = new R7UndertowHandler(serverConfig, routeRegistry, journalWriter, errorHandler, scheduler);
        hotReloadService.onReload(r7UndertowHandler::evictProxyCache);
        final TrafficMetricsHandler trafficMetricsHandler = new TrafficMetricsHandler(r7UndertowHandler);

        final HttpHandler rootHandler = Handlers.path()
                .addPrefixPath("/", trafficMetricsHandler);

        final Undertow.Builder builder = Undertow.builder();
        builder.setWorker(sharedWorker);
        this.gracefulShutdownHandler = new GracefulShutdownHandler(rootHandler);
        builder.setHandler(gracefulShutdownHandler);
        configureServer(builder, serverConfig);
        final Undertow server = builder.build();

        final StatusHandler statusHandler = new StatusHandler(metricsRegistry, serverConfig, routeRegistry);

        final Undertow managementServer = setupStatusBackend(statusHandler, serverConfig.management().port(), serverConfig.management().host(), sharedWorker);

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            logger.info("Shutdown signal received. Initiating graceful shutdown sequence...");

            logger.info("Rejecting new requests and draining in-flight traffic...");
            gracefulShutdownHandler.shutdown();
            try
            {
                gracefulShutdownHandler.awaitShutdown(10_000);
            }
            catch (final InterruptedException e)
            {
                logger.warn("Interrupted while waiting for in-flight requests to drain");
                Thread.currentThread().interrupt();
            }

            logger.info("Stopping Undertow server...");
            server.stop();

            logger.info("Closing compression engine...");
            this.compressionEngine.close();

            logger.info("Shutting down journal writer...");
            journalWriter.shutdown();

            logger.info("Shutdown sequence complete");

        }, "r7-shutdown-hook"
        ));

        server.start();

        // Have to happen after start
        statusHandler.setConnectorStatistics(server.getListenerInfo().getFirst().getConnectorStatistics());

        final Duration uptime = SystemUtil.getUptime();
        logger.info("🚀 ethlo r7 Gateway - version {}, started in {}ms", VersionProvider.getVersion(), uptime.toMillis());
        logger.info("Gateway listening on {}", server.getListenerInfo().stream().map(Undertow.ListenerInfo::getAddress).toList());
        logger.info("Management listening on {}", managementServer.getListenerInfo().stream().map(Undertow.ListenerInfo::getAddress).toList());
    }

    private static ServerConfig loadServerSettings(final Path serverFile)
    {
        if (!Files.exists(serverFile))
        {
            logger.info("No server.yaml file found at {}. Using defaults", serverFile.toAbsolutePath());
            return ServerConfig.standard();
        }

        logger.info("Loading server settings from {}", serverFile.toAbsolutePath());
        ServerConfig serverConfig = ConfigurationManager.load(serverFile, ServerConfig.class);
        if (serverConfig == null)
        {
            logger.warn("No settings found in server.yaml, using only defaults");
            serverConfig = ServerConfig.standard();
        }
        final ValidationResult result = new ValidationResult();
        serverConfig.validate(result);
        result.throwIfInvalid();
        return serverConfig;
    }

    private static MetricsRegistry setupMetricsRegistry(final Path workDir, final GatewayScheduler scheduler)
    {
        final TelemetryRepository telemetryRepository = new FileTelemetryRepository(workDir);
        return new MetricsRegistry(telemetryRepository, scheduler);
    }

    public static void main(final String[] args) throws IOException
    {
        // Force all silent background thread crashes to print to standard error
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
        {
            if (e instanceof ConfigurationException)
            {
                logger.error(e.getMessage());
                System.exit(1);
            }
            else
            {
                System.err.println("FATAL THREAD CRASH [" + t.getName() + "]: " + e.getMessage());
                e.printStackTrace(System.err);
                System.exit(2);
            }
        });

        setupLogging();

        logger.debug("Main class started at {} ms since process start", SystemUtil.getUptime());

        final String routesPath = System.getenv().getOrDefault("R7_ROUTES_CONFIG", "config/routes.yaml");
        final String serverPath = System.getenv().getOrDefault("R7_SERVER_CONFIG", "config/server.yaml");

        new R7Main(Paths.get(routesPath), Paths.get(serverPath));
    }

    private static void setupLogging()
    {
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        try (final InputStream configStream = getLoggerConfigStream())
        {
            final JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);

            // This clears all existing configuration
            context.reset();

            // Load the user's XML configuration
            configurator.doConfigure(configStream);

            // Inject the telemetry appender after XML is loaded so it is not reset
            final ParserRejectionAppender appender = new ParserRejectionAppender();
            appender.setContext(context);
            appender.start();
        }
        catch (final JoranException | IOException je)
        {
            System.err.println("FATAL: Logback failed to configure from XML: " + je.getMessage());
        } finally
        {
            new StatusPrinter2().printInCaseOfErrorsOrWarnings(context);
        }
    }

    private static InputStream getLoggerConfigStream()
    {
        final String logbackConfigPath = System.getenv().getOrDefault("R7_LOGBACK_CONFIG", "config/server.yaml");
        final Path configFilePath = Paths.get(logbackConfigPath).toAbsolutePath();
        if (Files.exists(configFilePath) && Files.isRegularFile(configFilePath))
        {
            try
            {
                return Files.newInputStream(configFilePath, StandardOpenOption.READ);
            }
            catch (IOException e)
            {
                System.err.println("FATAL: Logback failed to read from config file: " + e.getMessage());
            }
        }

        final InputStream defaultConfig = R7Main.class.getResourceAsStream("/default-logback.xml");
        if (defaultConfig == null)
        {
            throw new IllegalStateException("FATAL: Unable to load logback configuration from config/logback.xml or classpath:/default-logback.xml");
        }

        return defaultConfig;
    }

    private XnioWorker createSharedWorker(final ServerConfig config) throws IOException
    {
        final ServerConfig.AdvancedConfig advanced = config.advanced();
        final OptionMap.Builder options = OptionMap.builder()
                .set(Options.WORKER_IO_THREADS, advanced.ioThreads())
                .set(Options.WORKER_TASK_CORE_THREADS, advanced.taskThreads())
                .set(Options.WORKER_TASK_MAX_THREADS, advanced.taskThreads())
                .set(Options.CONNECTION_HIGH_WATER, advanced.connectionHighWater())
                .set(Options.CONNECTION_LOW_WATER, advanced.connectionLowWater());

        return Xnio.getInstance().createWorker(options.getMap());
    }

    private Undertow setupStatusBackend(final StatusHandler statusHandler, final int port, final String host, final XnioWorker sharedWorker)
    {
        final Undertow.Builder statusServer = Undertow.builder();
        final HttpHandler targetHandler = Handlers.path()
                .addPrefixPath("/", statusHandler);

        statusServer.setHandler(targetHandler);
        statusServer.setWorker(sharedWorker);
        statusServer.addHttpListener(port, host);
        final Undertow server = statusServer.build();
        server.start();
        return server;
    }

    private void configureServer(final Undertow.Builder builder, final ServerConfig config)
    {
        final ServerConfig.ServerCoreConfig server = config.server();
        final ServerConfig.AdvancedConfig advanced = config.advanced();
        final ServerConfig.LimitsConfig limits = config.limits();
        final ServerConfig.HttpConfig http = config.http();

        builder.addHttpListener(server.port(), server.host())
                // Socket Layer
                .setSocketOption(Options.TCP_NODELAY, advanced.tcpNoDelay())
                .setSocketOption(Options.REUSE_ADDRESSES, advanced.reuseAddresses())
                .setSocketOption(Options.BACKLOG, advanced.socketBacklog())
                .setSocketOption(Options.READ_TIMEOUT, (int) advanced.socketReadTimeout().toMillis())

                // HTTP Protocol
                .setServerOption(UndertowOptions.MAX_HEADER_SIZE, (int) limits.maxHeaderSize().toBytes())
                .setServerOption(UndertowOptions.REQUEST_PARSE_TIMEOUT, (int) http.requestParseTimeout().toMillis())
                .setServerOption(UndertowOptions.MAX_ENTITY_SIZE, limits.maxEntitySize().toBytes())
                .setServerOption(UndertowOptions.ENABLE_HTTP2, http.enableHttp2())
                .setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE, http.alwaysSetKeepAlive())

                // Limits
                .setServerOption(UndertowOptions.MAX_HEADERS, limits.maxHeaderCount())
                .setServerOption(UndertowOptions.MAX_PARAMETERS, limits.maxParameterCount())
                .setServerOption(UndertowOptions.MAX_COOKIES, limits.maxCookieCount())

                // Options
                .setServerOption(UndertowOptions.ENABLE_STATISTICS, advanced.enableStatistics())
                .setServerOption(UndertowOptions.RECORD_REQUEST_START_TIME, advanced.recordRequestStartTime())

                // Memory Configuration
                .setDirectBuffers(advanced.directBuffers());
    }
}
