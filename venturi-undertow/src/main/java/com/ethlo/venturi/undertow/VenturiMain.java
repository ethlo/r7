package com.ethlo.venturi.undertow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import com.ethlo.venturi.config.RouteRegistry;
import com.ethlo.venturi.config.VenturiLoader;
import com.ethlo.venturi.constants.HttpStatuses;
import com.ethlo.venturi.constants.MediaTypes;
import com.ethlo.venturi.core.DefaultGatewayExchangeDataWriter;
import com.ethlo.venturi.core.GatewayExchangeDataWriter;
import com.ethlo.venturi.core.storage.ShardedStorageLayoutStrategy;
import com.ethlo.venturi.undertow.config.ServerConfig;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.accesslog.AccessLogHandler;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
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

        loader.load(configFile, routeRegistry, (def, dataRoute) -> {
                    final ServerConfig.ProxyConfig pConfig = serverConfig.proxy();

                    final ProxyHandler proxyHandler = hostProxyCache.computeIfAbsent(dataRoute.uri(), uri ->
                            {
                                final LoadBalancingProxyClient client = new LoadBalancingProxyClient()
                                        .addHost(java.net.URI.create(uri.toString()))
                                        .setConnectionsPerThread(pConfig.connectionsPerThread())
                                        .setTtl(pConfig.ttl());

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
                .addPrefixPath("/", new VenturiUndertowHandler(routeRegistry, gatewayExchangeDataWriter));

        final AccessLogHandler accessLogHandler = new AccessLogHandler(
                rootHandler,
                new Slf4jAccessLogReceiver(LoggerFactory.getLogger("venturi.access")),
                "combined", // Provides: IP, Date, Method, Path, Status, Bytes, Referer, User-Agent
                VenturiMain.class.getClassLoader()
        );

        final Undertow.Builder builder = Undertow.builder()
                .setHandler(accessLogHandler);

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
        //final RuntimeMXBean rb = ManagementFactory.getRuntimeMXBean();
        //return rb.getUptime();
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
            // Important: clear the "build-time" state
            context.reset();
            // Load the XML from the classpath (src/main/resources)
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
                .setSocketOption(org.xnio.Options.TCP_NODELAY, socket.tcpNodelay())
                .setSocketOption(org.xnio.Options.REUSE_ADDRESSES, socket.reuseAddresses())
                .setSocketOption(org.xnio.Options.BACKLOG, socket.backlog())

                // Worker Layer
                .setWorkerOption(org.xnio.Options.WORKER_IO_THREADS, worker.ioThreads())
                .setWorkerOption(org.xnio.Options.WORKER_TASK_CORE_THREADS, worker.taskCoreThreads())
                .setWorkerOption(org.xnio.Options.WORKER_TASK_MAX_THREADS, worker.taskMaxThreads())
                .setWorkerOption(org.xnio.Options.STACK_SIZE, worker.stackSize())
                .setWorkerOption(org.xnio.Options.CONNECTION_HIGH_WATER, worker.connectionHighWater())
                .setWorkerOption(org.xnio.Options.CONNECTION_LOW_WATER, worker.connectionLowWater())

                // Protocol & Memory
                .setServerOption(io.undertow.UndertowOptions.ENABLE_HTTP2, opts.enableHttp2())
                .setServerOption(io.undertow.UndertowOptions.ALWAYS_SET_KEEP_ALIVE, opts.alwaysSetKeepAlive())
                .setBufferSize(opts.bufferSize())
                .setDirectBuffers(opts.directBuffers());
    }
}