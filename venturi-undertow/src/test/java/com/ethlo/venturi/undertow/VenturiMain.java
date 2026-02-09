package com.ethlo.venturi.undertow;

import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.core.filters.CorrelationIdFilter;
import com.ethlo.venturi.core.filters.RequireAuthorizationHeaderFilter;
import com.ethlo.venturi.core.predicates.RegexPathPredicate;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.util.Headers;
import org.xnio.Options;

import java.net.URI;
import java.util.List;

public final class VenturiMain {
    public static void main(String[] args) {
        final URI target = URI.create("http://localhost:8090/baz.txt");

        // 1. TUNE: The High-Capacity Proxy Client
        final var proxyClient = new LoadBalancingProxyClient()
                .setConnectionsPerThread(1000) // Huge pool for 100k/sec
                .setSoftMaxConnectionsPerThread(800)
                .setTtl(60000)
                .addHost(target);

        final var proxyHandler = ProxyHandler.builder()
                .setProxyClient(proxyClient)
                .setMaxRequestTime(-1)
                .setNext(ResponseCodeHandler.HANDLE_404)
                .build();

        final var route = new GatewayRoute() {
            @Override
            public CharSequence id() {
                return "nitro-route";
            }

            @Override
            public CharSequence uri() {
                return target.toString();
            }

            @Override
            public GatewayPredicate predicate() {
                //return new PathPredicate("/foobar", false);
                return new RegexPathPredicate("^/foobar");
            }

            @Override
            public Iterable<GatewayFilter> filters() {
                return List.of(new CorrelationIdFilter(), new RequireAuthorizationHeaderFilter()); //, new GhostProxyFilter());
            }
        };

        final int processors = Runtime.getRuntime().availableProcessors();

        final HttpHandler benchMarkHandler = exchange -> {
            // High-speed response without touching the heap
            exchange.setStatusCode(200);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
            exchange.getResponseSender().send("OK");
        };

        // Add it to your PathHandler as a separate path
        final HttpHandler rootHandler = Handlers.path()
                .addPrefixPath("/foobar", new VenturiUndertowHandler(route, proxyHandler))
                .addExactPath("/benchmark", benchMarkHandler);

        final Undertow server = Undertow.builder()
                .addHttpListener(9999, "0.0.0.0")

                // SOCKET & TCP LAYER (Fixes the 15k bottleneck)
                .setSocketOption(Options.TCP_NODELAY, true)         // Disable Nagle's
                .setSocketOption(Options.REUSE_ADDRESSES, true)     // Fast socket recycling
                .setSocketOption(Options.BACKLOG, 10000)            // Handle massive bursts

                // WORKER LAYER
                .setWorkerOption(Options.WORKER_IO_THREADS, processors * 2)
                .setWorkerOption(Options.CONNECTION_HIGH_WATER, 100000)
                .setWorkerOption(Options.CONNECTION_LOW_WATER, 90000)

                // PROTOCOL LAYER
                .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
                .setServerOption(UndertowOptions.RECORD_REQUEST_START_TIME, false)
                .setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE, true)

                // ZERO-COPY MEMORY LAYER
                .setBufferSize(16 * 1024)      // 16KB Sweet spot
                .setDirectBuffers(true)        // Off-heap memory (Zero copy)

                .setHandler(rootHandler)
                .build();

        server.start();

        System.out.println("🚀 " + server.getListenerInfo().getFirst().getAddress() + "  -> " + target);
    }
}