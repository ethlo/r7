package com.ethlo.r7.undertow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.config.ConfigurationManager;
import com.ethlo.r7.config.RouteRegistry;
import com.ethlo.r7.config.RoutesDefinition;
import com.ethlo.r7.core.StandardErrorHandler;
import com.ethlo.r7.spi.EngineContext;
import com.ethlo.r7.undertow.config.ServerConfig;
import com.sun.net.httpserver.HttpServer;

/**
 * Upstream contexts, and with them the health monitors, exist from the moment routes are loaded:
 * a dead target is noticed before any request is sent to it, and a reload stops the monitors of
 * the generation it replaces.
 */
class UpstreamContextLifecycleTest
{
    private static final long TIMEOUT_MS = 5_000;

    @TempDir
    Path dir;

    private final GatewayScheduler scheduler = new GatewayScheduler(2);
    private final RouteRegistry registry = new RouteRegistry();
    private final ConfigurationManager configurationManager = new ConfigurationManager(new EngineContext(Map.of()));
    private HttpServer upstream;
    private final AtomicInteger firstProbes = new AtomicInteger();
    private final AtomicInteger secondProbes = new AtomicInteger();

    @BeforeEach
    void startUpstream() throws IOException
    {
        this.upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.upstream.createContext("/first-health", exchange -> this.answer(exchange, this.firstProbes));
        this.upstream.createContext("/second-health", exchange -> this.answer(exchange, this.secondProbes));
        this.upstream.start();
    }

    @AfterEach
    void stop()
    {
        this.upstream.stop(0);
        this.scheduler.shutdown();
    }

    @Test
    void healthChecksRunBeforeTheRouteSeesAnyTraffic() throws Exception
    {
        this.loadRoutes(this.route("first"));

        new R7UndertowHandler(ServerConfig.standard(), this.registry, null, new StandardErrorHandler(), this.scheduler);

        awaitAtLeast(this.firstProbes, 2);
    }

    @Test
    void reloadStartsTheNewMonitorsAndStopsTheOldOnes() throws Exception
    {
        this.loadRoutes(this.route("first"));
        final R7UndertowHandler handler = new R7UndertowHandler(ServerConfig.standard(), this.registry, null, new StandardErrorHandler(), this.scheduler);
        awaitAtLeast(this.firstProbes, 1);

        this.loadRoutes(this.route("second"));
        handler.reloadState();

        awaitAtLeast(this.secondProbes, 2);
        // Probes to the first route may have been in flight at the reload; after that, none.
        final int firstAfterReload = this.firstProbes.get();
        TimeUnit.MILLISECONDS.sleep(300);
        assertThat(this.firstProbes.get()).as("probes of the replaced route").isEqualTo(firstAfterReload);
    }

    private void answer(final com.sun.net.httpserver.HttpExchange exchange, final AtomicInteger counter) throws IOException
    {
        counter.incrementAndGet();
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    private String route(final String id)
    {
        return """
                  - id: %s
                    match:
                      - PathPrefix:
                          prefix: /%s
                    upstream:
                      targets:
                        - url: http://127.0.0.1:%d
                      health_check:
                        path: /%s-health
                        interval: 50ms
                """.formatted(id, id, this.upstream.getAddress().getPort(), id);
    }

    private void loadRoutes(final String routes) throws IOException
    {
        final Path file = this.dir.resolve("routes.yaml");
        Files.writeString(file, "version: test\nroutes:\n" + routes);
        this.configurationManager.load(ConfigurationManager.load(file, RoutesDefinition.class), this.registry);
    }

    private static void awaitAtLeast(final AtomicInteger counter, final int expected) throws InterruptedException
    {
        final long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (counter.get() < expected && System.currentTimeMillis() < deadline)
        {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(counter.get()).as("health probes received").isGreaterThanOrEqualTo(expected);
    }
}
