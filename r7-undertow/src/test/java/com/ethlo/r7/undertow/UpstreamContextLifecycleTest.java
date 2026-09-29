package com.ethlo.r7.undertow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.config.ConfigurationManager;
import com.ethlo.r7.config.RouteRegistry;
import com.ethlo.r7.config.RoutesDefinition;
import com.ethlo.r7.core.StandardErrorHandler;
import com.ethlo.r7.spi.EngineContext;
import com.ethlo.r7.undertow.config.ServerConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Upstream contexts, and with them the health monitors, exist from the moment a generation of
 * routes is prepared: a dead target is noticed before any request is sent to it, a replaced
 * generation's monitors stop, and a generation that fails to prepare leaves none running.
 */
class UpstreamContextLifecycleTest
{
    private static final long TIMEOUT_MS = 5_000;

    @TempDir
    Path dir;

    private final GatewayScheduler scheduler = new GatewayScheduler(2);
    private final RouteRegistry registry = new RouteRegistry();
    private final ConfigurationManager configurationManager = new ConfigurationManager(new EngineContext(Map.of()));
    private final AtomicInteger firstProbes = new AtomicInteger();
    private final AtomicInteger secondProbes = new AtomicInteger();
    private HttpServer upstream;

    @BeforeEach
    void startUpstream() throws IOException
    {
        this.upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.upstream.createContext("/first-health", exchange -> answer(exchange, this.firstProbes));
        this.upstream.createContext("/second-health", exchange -> answer(exchange, this.secondProbes));
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
        this.registry.updateRoutes("test", this.build(this.route("first", "")));

        this.newHandler();

        awaitAtLeast(this.firstProbes, 2);
    }

    @Test
    void reloadStartsTheNewMonitorsAndStopsTheOldOnes() throws Exception
    {
        this.registry.updateRoutes("test", this.build(this.route("first", "")));
        final R7UndertowHandler handler = this.newHandler();
        awaitAtLeast(this.firstProbes, 1);

        // What HotReloadService does: prepare, publish, retire the replaced generation
        final List<GatewayRoute> first = this.registry.getRoutes();
        final List<GatewayRoute> second = this.build(this.route("second", ""));
        handler.prepare(second);
        awaitAtLeast(this.secondProbes, 2);
        this.registry.updateRoutes("test", second);
        handler.retire(first);

        assertStopped(this.firstProbes, "probes of the replaced route");
    }

    @Test
    void aGenerationThatFailsToPrepareLeavesNoMonitorRunning() throws Exception
    {
        this.registry.updateRoutes("test", this.build(this.route("first", "")));
        final R7UndertowHandler handler = this.newHandler();

        // A read timeout that passes validation but does not fit the proxy client's int
        // milliseconds: preparing fails on the third route, after the second's monitor started.
        final List<GatewayRoute> rejected = this.build(this.route("second", "") + this.route("third", """
                      timeouts:
                        read: 30d
                """));
        assertThatThrownBy(() -> handler.prepare(rejected)).isInstanceOf(ArithmeticException.class);

        assertStopped(this.secondProbes, "probes of the rejected generation");
        final int first = this.firstProbes.get();
        awaitAtLeast(this.firstProbes, first + 2);
    }

    private R7UndertowHandler newHandler()
    {
        return new R7UndertowHandler(ServerConfig.standard(), this.registry, null, new StandardErrorHandler(), this.scheduler);
    }

    private String route(final String id, final String extraUpstreamConfig)
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
                %s""".formatted(id, id, this.upstream.getAddress().getPort(), id, extraUpstreamConfig);
    }

    private List<GatewayRoute> build(final String routes) throws IOException
    {
        final Path file = this.dir.resolve("routes.yaml");
        Files.writeString(file, "version: test\nroutes:\n" + routes);
        return this.configurationManager.build(ConfigurationManager.load(file, RoutesDefinition.class));
    }

    private static void answer(final HttpExchange exchange, final AtomicInteger counter) throws IOException
    {
        counter.incrementAndGet();
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
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

    private static void assertStopped(final AtomicInteger counter, final String description) throws InterruptedException
    {
        // A probe may already have been in flight when the monitor stopped; after that, none.
        TimeUnit.MILLISECONDS.sleep(100);
        final int settled = counter.get();
        TimeUnit.MILLISECONDS.sleep(300);
        assertThat(counter.get()).as(description).isEqualTo(settled);
    }
}
