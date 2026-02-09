package com.ethlo.venturi.undertow;

import java.net.URI;
import java.util.Collections;

import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.Route;
import com.ethlo.venturi.core.PathPredicate;

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.client.UndertowClient;

public final class VenturiMain {

    public static void main(final String[] args) {
        final String listenAddress = "0.0.0.0";
        final int listenPort = 8080;
        final String targetUri = "http://localhost:8090";

        // 1. Define the Route: Prefix match on /foobar
        final Route route = new Route() {
            private final GatewayPredicate pathPredicate = new PathPredicate("/foobar", false);

            @Override
            public CharSequence id() { return "foobar-service"; }

            @Override
            public CharSequence uri() { return targetUri; }

            @Override
            public GatewayPredicate predicate() { return pathPredicate; }

            @Override
            public Iterable<GatewayFilter> filters() { return Collections.emptyList(); }
        };

        // 2. Initialize the Undertow Client (used internally by ProxyHandler)
        final UndertowClient client = UndertowClient.getInstance();

        // 3. Start the Server with Performance Tuning for 100k req/sec
        final Undertow server = Undertow.builder()
                .addHttpListener(listenPort, listenAddress)
                .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
                .setServerOption(UndertowOptions.TCP_NODELAY, true)
                .setServerOption(UndertowOptions.RECORD_REQUEST_START_TIME, false) // Save cycles
                .setHandler(new VenturiUndertowHandler(route, client))
                .build();

        System.out.println("🚀 Ethlo Venturi Gateway Active");
        System.out.println("📍 Listening: " + listenAddress + ":" + listenPort);
        System.out.println("🔗 Route: /foobar -> " + targetUri);
        
        server.start();
    }
}