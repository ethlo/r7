package com.ethlo.venturi.undertow;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Paths;
import java.util.List;

import com.ethlo.venturi.core.filters.StripCacheHeadersFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.Options;

import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.constants.HttpStatuses;
import com.ethlo.venturi.constants.MediaTypes;
import com.ethlo.venturi.core.DataBufferRepository;
import com.ethlo.venturi.core.DefaultDataBufferRepository;
import com.ethlo.venturi.core.filters.CorrelationIdFilter;
import com.ethlo.venturi.core.predicates.RegexPathPredicate;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyClient;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.util.Headers;

public final class VenturiMain
{
    private static final Logger logger = LoggerFactory.getLogger(VenturiMain.class);

    public static void main(String[] args) throws IOException
    {
        final URI target = URI.create("http://localhost:8090/baz.txt");

        final ProxyClient proxyClient = new LoadBalancingProxyClient()
                .setConnectionsPerThread(1000) // Huge pool for 100k/sec
                .setSoftMaxConnectionsPerThread(800)
                .setTtl(60000)
                .addHost(target);

        final ProxyHandler proxyHandler = ProxyHandler.builder()
                .setProxyClient(proxyClient)
                .setMaxRequestTime(-1)
                .setNext(ResponseCodeHandler.HANDLE_404)
                .build();

        final GatewayRoute route = new GatewayRoute()
        {
            @Override
            public CharSequence id()
            {
                return "nitro-route";
            }

            @Override
            public CharSequence uri()
            {
                return target.toString();
            }

            @Override
            public GatewayPredicate predicate()
            {
                return new RegexPathPredicate("^/foobar");
            }

            @Override
            public Iterable<GatewayFilter> filters()
            {
                return List.of(new CorrelationIdFilter(), new StripCacheHeadersFilter());
                //, new RequireAuthorizationHeaderFilter());
                // , new GhostProxyFilter());
            }
        };

        final HttpHandler benchMarkHandler = exchange -> {
            exchange.setStatusCode(HttpStatuses.OK);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, MediaTypes.TEXT_PLAIN);
            exchange.getResponseSender().send("OK");
        };

        final DataBufferRepository repository = new DefaultDataBufferRepository(Paths.get("/tmp/venturi/logs"), 0L);
        // Add it to your PathHandler as a separate path
        final HttpHandler rootHandler = Handlers.path()
                .addPrefixPath("/foobar", new VenturiUndertowHandler(route, proxyHandler, repository))
                .addExactPath("/benchmark", benchMarkHandler);


        final Undertow.Builder builder = Undertow.builder()
                .addHttpListener(9999, "0.0.0.0")
                .setHandler(rootHandler);
        performanceTune(builder);
        final Undertow server = builder.build();
        server.start();

        logger.info("🚀 " + server.getListenerInfo().getFirst().getAddress() + "  -> " + target);
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