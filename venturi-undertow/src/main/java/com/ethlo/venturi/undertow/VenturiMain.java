package com.ethlo.venturi.undertow;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.Options;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import com.ethlo.venturi.api.GatewayErrorHandler;
import com.ethlo.venturi.config.RouteRegistry;
import com.ethlo.venturi.config.VenturiLoader;
import com.ethlo.venturi.constants.HttpStatuses;
import com.ethlo.venturi.constants.MediaTypes;
import com.ethlo.venturi.core.DefaultGatewayExchangeDataWriter;
import com.ethlo.venturi.core.GatewayExchangeDataWriter;
import com.ethlo.venturi.core.StandardErrorHandler;
import com.ethlo.venturi.core.storage.ShardedStorageLayoutStrategy;
import com.ethlo.venturi.undertow.config.ServerConfig;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.accesslog.AccessLogHandler;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyClient;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.util.Headers;

public final class VenturiMain
{
    private static final Logger logger = LoggerFactory.getLogger(VenturiMain.class);

    private final Map<CharSequence, ProxyHandler> hostProxyCache = new HashMap<>();

    public VenturiMain(Path configFile, Path serverFile) throws IOException
    {
        final HttpHandler benchMarkHandler = exchange -> {
            exchange.setStatusCode(HttpStatuses.OK);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, MediaTypes.TEXT_PLAIN);
            exchange.getResponseSender().send("OK");
        };

        final RouteRegistry routeRegistry = new RouteRegistry();
        final VenturiLoader loader = new VenturiLoader();

        final ServerConfig serverConfig = loader.load(serverFile, ServerConfig.class);
        logger.info("Loaded server config: {}", serverConfig);

        final GatewayErrorHandler errorHandler = new StandardErrorHandler();

        loader.load(configFile, routeRegistry, (def, dataRoute) -> {
                    final ServerConfig.ProxyConfig pConfig = serverConfig.proxy();

                    final ProxyHandler proxyHandler = hostProxyCache.computeIfAbsent(dataRoute.uri(), uri ->
                            {
                                final ProxyClient client = new DiagnosticProxyClient(new LoadBalancingProxyClient()
                                        .addHost(URI.create(uri.toString()))
                                        .setConnectionsPerThread(pConfig.connectionsPerThread())
                                        .setMaxQueueSize(serverConfig.proxy().maxQueueSize())
                                        .setTtl(pConfig.ttl()), errorHandler
                                );

                                return ProxyHandler.builder()
                                        .setProxyClient(client)
                                        .setMaxRequestTime(pConfig.maxRequestTime())
                                        .setReuseXForwarded(true)
                                        .build();
                            }
                    );


                    // Return the Executable version that bridges Core <--> Undertow
                    return new VenturiExecutableRoute(def, dataRoute, proxyHandler);
                }
        );

        final ServerConfig.StorageConfig storage = serverConfig.storage();

        final GatewayExchangeDataWriter gatewayExchangeDataWriter = new DefaultGatewayExchangeDataWriter(
                Paths.get(storage.tempDir()),
                storage.memoryThreshold(),
                new ShardedStorageLayoutStrategy()
        );

        final HttpHandler rootHandler = Handlers.path()
                .addExactPath("/benchmark", benchMarkHandler)
                .addPrefixPath("/", new VenturiUndertowHandler(routeRegistry, gatewayExchangeDataWriter, errorHandler));

        final AccessLogHandler accessLogHandler = new AccessLogHandler(
                rootHandler,
                new Slf4jAccessLogReceiver(LoggerFactory.getLogger("venturi.access")),
                "combined", // Provides: IP, Date, Method, Path, Status, Bytes, Referer, User-Agent
                VenturiMain.class.getClassLoader()
        );

        final Undertow.Builder builder = Undertow.builder();

        builder.setHandler(accessLogHandler);

        configureServer(builder, serverConfig);

        final Undertow server = builder.build();

        // Explicit Shutdown Hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received.");
            server.stop();
        }, "shutdown-hook"
        ));

        server.start();

        long uptime = getUptime();
        logger.info("🚀 Started Venturi in {}ms, listening at {}", uptime, server.getListenerInfo().getFirst().getAddress());
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

                // Worker Layer
                .setWorkerOption(Options.WORKER_IO_THREADS, worker.ioThreads())
                .setWorkerOption(Options.WORKER_TASK_CORE_THREADS, worker.taskCoreThreads())
                .setWorkerOption(Options.WORKER_TASK_MAX_THREADS, worker.taskMaxThreads())
                .setWorkerOption(Options.STACK_SIZE, worker.stackSize())
                .setWorkerOption(Options.CONNECTION_HIGH_WATER, worker.connectionHighWater())
                .setWorkerOption(Options.CONNECTION_LOW_WATER, worker.connectionLowWater())

                // Protocol & Memory
                .setServerOption(UndertowOptions.ENABLE_HTTP2, opts.enableHttp2())
                .setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE, opts.alwaysSetKeepAlive())
                .setBufferSize(opts.bufferSize())
                .setDirectBuffers(opts.directBuffers());
    }
}