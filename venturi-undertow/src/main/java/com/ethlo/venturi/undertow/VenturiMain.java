package com.ethlo.venturi.undertow;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Xnio;
import org.xnio.ssl.XnioSsl;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import com.ethlo.venturi.ShardedJournalWriter;
import com.ethlo.venturi.api.GatewayErrorHandler;
import com.ethlo.venturi.config.RouteRegistry;
import com.ethlo.venturi.config.VenturiLoader;
import com.ethlo.venturi.constants.HttpStatuses;
import com.ethlo.venturi.constants.MediaTypes;
import com.ethlo.venturi.core.StandardErrorHandler;
import com.ethlo.venturi.undertow.config.ServerConfig;
import com.ethlo.venturi.vlf.AsyncSegmentProvider;
import com.ethlo.venturi.vlf.VlfJournal;
import com.ethlo.venturi.vlf.VlfJournalProvider;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.protocols.ssl.UndertowXnioSsl;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyClient;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.util.Headers;

public final class VenturiMain
{
    public static final int JOURNAL_SHARD_COUNT = 4;
    public static final int JOURNAL_SHARD_SIZE_BYTES = 100_000_000;
    private static final Logger logger = LoggerFactory.getLogger(VenturiMain.class);
    private static final long JOURNAL_SHARD_INDEX_SIZE_BYTES = 10_000_000;
    private static final ByteBuffer OK = ByteBuffer.wrap("OK".getBytes(StandardCharsets.UTF_8));
    private final XnioSsl xnioSsl;
    private final Map<CharSequence, HttpHandler> routeProxyCache = new HashMap<>();

    public VenturiMain(Path configFile, Path serverFile) throws IOException
    {
        this.xnioSsl = getXnioSsl();

        final HttpHandler benchMarkHandler = exchange -> {
            exchange.setStatusCode(HttpStatuses.OK);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, MediaTypes.TEXT_PLAIN);
            exchange.getResponseSender().send(OK.duplicate());
        };

        final RouteRegistry routeRegistry = new RouteRegistry();
        final VenturiLoader loader = new VenturiLoader();

        final ServerConfig serverConfig = loader.load(serverFile, ServerConfig.class);
        logger.debug("Loaded server config: {}", serverConfig);

        final boolean logProxyError = true;
        final GatewayErrorHandler errorHandler = new StandardErrorHandler();

        loader.load(configFile, routeRegistry, (def, dataRoute) -> {
                    final ServerConfig.ProxyConfig pConfig = serverConfig.proxy();

                    final HttpHandler proxyHandler = routeProxyCache.computeIfAbsent(dataRoute.id(), uri ->
                            {
                                final LoadBalancingProxyClient rawClient = new LoadBalancingProxyClient()
                                        .setConnectionsPerThread(pConfig.connectionsPerThread())
                                        .setMaxQueueSize(serverConfig.proxy().maxQueueSize())
                                        .setTtl(pConfig.ttl());

                                dataRoute.uri().stream()
                                        .map(CharSequence::toString)
                                        .map(URI::create)
                                        .forEach(u -> {
                                            if ("https".equalsIgnoreCase(u.getScheme()))
                                            {
                                                rawClient.addHost(u, xnioSsl);
                                            }
                                            else
                                            {
                                                rawClient.addHost(u);
                                            }
                                        });

                                final ProxyClient client = logProxyError ? new DiagnosticProxyClient(rawClient, errorHandler) : rawClient;

                                return ProxyHandler.builder()
                                        .setProxyClient(client)
                                        .setMaxRequestTime(pConfig.maxRequestTime())
                                        .setReuseXForwarded(false)
                                        .setRewriteHostHeader(true)
                                        .build();
                            }
                    );

                    // Return the Executable version that bridges Core <--> Undertow
                    return new VenturiExecutableRoute(def, dataRoute, proxyHandler);
                }
        );

        new VenturiConsolePrinter().printFullReport(serverConfig, routeRegistry.getRoutes());

        final ServerConfig.StorageConfig storage = serverConfig.storage();

        final Path rootDir = Paths.get(storage.tempDir());
        Files.createDirectories(rootDir);

        final ShardedJournalWriter<VlfJournal> gatewayExchangeDataWriter = new ShardedJournalWriter<>(JOURNAL_SHARD_COUNT, shardIdx -> {
            final VlfJournalProvider provider = new VlfJournalProvider(rootDir, shardIdx);
            return new VlfJournal(new AsyncSegmentProvider(JOURNAL_SHARD_SIZE_BYTES, provider, 1));
        }
        );

        final HttpHandler rootHandler = Handlers.path()
                .addExactPath("/benchmark", benchMarkHandler)
                .addPrefixPath("/", new VenturiUndertowHandler(routeRegistry, gatewayExchangeDataWriter, errorHandler));

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
            server.stop();
        }, "shutdown-hook"
        ));

        server.start();

        long uptime = getUptime();
        logger.info("🚀 Started in {}ms, listening at {}", uptime, server.getListenerInfo().stream().map(Undertow.ListenerInfo::getAddress).toList());
    }

    private static UndertowXnioSsl getXnioSsl()
    {
        try
        {
            return new UndertowXnioSsl(Xnio.getInstance(), OptionMap.EMPTY);
        }
        catch (NoSuchProviderException | NoSuchAlgorithmException | KeyManagementException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private static void setupTestBackEndForProxy(HttpHandler benchMarkHandler)
    {
        final Undertow.Builder target = Undertow.builder();
        final HttpHandler targetHandler = Handlers.path()
                .addPrefixPath("/", benchMarkHandler);
        target.setHandler(targetHandler);
        target.addHttpListener(1111, "0.0.0.0");
        target.build().start();
    }

    private static long getUptime()
    {
        Instant start = ProcessHandle.current().info().startInstant().orElse(Instant.now());
        Duration uptime = Duration.between(start, Instant.now());
        return uptime.toMillis();
    }

    // MUST be public
    public static void main(String[] args) throws IOException
    {
        setupLogging();
        logger.debug("Main class started at {} ms since process start", getUptime());
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

    private void printBanner()
    {
        logger.info("------------------------------------------------------------------");
        logger.info("  _   _             _               _ ");
        logger.info(" | | | | ___ _ __  | |_ _   _ _ __ (_)");
        logger.info(" | | | |/ _ \\ '_ \\ | __| | | | '__|| |");
        logger.info(" \\ \\_/ /  __/ | | || |_| |_| | |   | |");
        logger.info("  \\___/ \\___|_| |_| \\__|\\__,_|_|   |_| ");
        logger.info("------------------------------------------------------------------");

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

                .setServerOption(UndertowOptions.ENABLE_HTTP2, opts.enableHttp2())
                .setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE, opts.alwaysSetKeepAlive())
                .setBufferSize(opts.bufferSize())
                .setDirectBuffers(opts.directBuffers());
    }
}