package com.ethlo.venturi.undertow;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.Options;

import com.ethlo.venturi.config.RouteRegistry;
import com.ethlo.venturi.config.VenturiLoader;
import com.ethlo.venturi.constants.HttpStatuses;
import com.ethlo.venturi.constants.MediaTypes;
import com.ethlo.venturi.core.DefaultGatewayExchangeDataWriter;
import com.ethlo.venturi.core.GatewayExchangeDataWriter;
import com.ethlo.venturi.core.storage.ShardedStorageLayoutStrategy;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.util.Headers;

public final class VenturiMain
{
    private static final Logger logger = LoggerFactory.getLogger(VenturiMain.class);

    private final Map<CharSequence, ProxyHandler> hostProxyCache = new HashMap<>();
    private final VenturiLoader loader = new VenturiLoader();

    public VenturiMain(Path configFile) throws IOException
    {
        final HttpHandler benchMarkHandler = exchange -> {
            exchange.setStatusCode(HttpStatuses.OK);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, MediaTypes.TEXT_PLAIN);
            exchange.getResponseSender().send("OK");
        };

        final GatewayExchangeDataWriter gatewayExchangeDataWriter = new DefaultGatewayExchangeDataWriter(Paths.get("/tmp/venturi"), 64_000, new ShardedStorageLayoutStrategy());

        // Add it to your PathHandler as a separate path
        final RouteRegistry routeRegistry = new RouteRegistry();

        loader.load(configFile, routeRegistry, (def, dataRoute) -> {
                    // Resolve or create the ProxyHandler for this host
                    final ProxyHandler proxyHandler = hostProxyCache.computeIfAbsent(dataRoute.uri(), uri -> {
                                final LoadBalancingProxyClient client = new LoadBalancingProxyClient()
                                        .addHost(java.net.URI.create(uri.toString()))
                                        .setConnectionsPerThread(1000)
                                        .setTtl(60000);

                                return ProxyHandler.builder()
                                        .setProxyClient(client)
                                        .setMaxRequestTime(-1)
                                        .build();
                            }
                    );

                    // Return the Executable version that bridges Core to Undertow
                    return new VenturiExecutableRoute(dataRoute, proxyHandler);
                }
        );

        final HttpHandler rootHandler = Handlers.path()
                .addExactPath("/benchmark", benchMarkHandler)
                .addPrefixPath("/", new VenturiUndertowHandler(routeRegistry, gatewayExchangeDataWriter));

        final Undertow.Builder builder = Undertow.builder()
                .addHttpListener(9999, "0.0.0.0")
                .setHandler(rootHandler);
        performanceTune(builder);
        final Undertow server = builder.build();
        server.start();

        logger.info("🚀 {}", server.getListenerInfo().getFirst().getAddress());
    }

    static void main(String[] args) throws IOException
    {
        final Path configFile = Paths.get(args.length == 0 ? "config.yaml" : args[0]);
        new VenturiMain(configFile);
    }

    private static void performanceTune(Undertow.Builder builder)
    {
        final int processors = Runtime.getRuntime().availableProcessors();
        builder.setSocketOption(Options.TCP_NODELAY, true)         // Disable Nagle's
                .setSocketOption(Options.REUSE_ADDRESSES, true)     // Fast socket recycling
                .setSocketOption(Options.BACKLOG, 1_000)            // Handle massive bursts

                // WORKER LAYER
                .setWorkerOption(Options.WORKER_IO_THREADS, processors * 2)
                .setWorkerOption(Options.CONNECTION_HIGH_WATER, 100000)
                .setWorkerOption(Options.CONNECTION_LOW_WATER, 90000)

                // PROTOCOL LAYER
                .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
                .setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE, true)

                // ZERO-COPY MEMORY LAYER
                .setBufferSize(16 * 1024)      // 16KB Sweet spot
                .setDirectBuffers(true);   // Off-heap memory (Zero copy)
    }
}