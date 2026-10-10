package com.ethlo.r7.server.blocking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import com.ethlo.r7.config.DefaultGatewayRoute;
import com.ethlo.r7.config.RouteDefinition;
import com.ethlo.r7.config.RouteRegistry;
import com.ethlo.r7.config.RoutesDefinition;
import com.ethlo.r7.config.TimeoutConfig;
import com.ethlo.r7.config.UpstreamConfig;
import com.ethlo.r7.core.StandardErrorHandler;
import com.ethlo.r7.server.GatewayPipeline;
import com.ethlo.r7.server.config.ServerConfig;
import com.ethlo.r7.spi.EngineContext;
import com.ethlo.r7.upstream.HttpUpstream;
import com.ethlo.r7.upstream.UpstreamOptions;
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
        final GatewayPipeline handler = this.newHandler();
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
        final GatewayPipeline handler = this.newHandler();

        // Preparing has to fail on the third route, after the second's monitor started. A read
        // timeout that does not fit the upstream client's int milliseconds does that, but validation
        // refuses one in a routes file, so the third route is rebuilt around it here.
        final List<GatewayRoute> built = this.build(this.route("second", "") + this.route("third", ""));
        final List<GatewayRoute> rejected = List.of(built.get(0), withReadTimeout(built.get(1), Duration.ofDays(30)));
        assertThatThrownBy(() -> handler.prepare(rejected)).isInstanceOf(ArithmeticException.class);

        assertStopped(this.secondProbes, "probes of the rejected generation");
        final int first = this.firstProbes.get();
        awaitAtLeast(this.firstProbes, first + 2);
    }

    /**
     * The route with its upstream read timeout replaced, constructed directly and so without the
     * validation {@link ConfigurationManager#build} would apply.
     */
    private static GatewayRoute withReadTimeout(final GatewayRoute route, final Duration read)
    {
        final DefaultGatewayRoute original = (DefaultGatewayRoute) route;
        final RouteDefinition definition = original.routeDefinition();
        final UpstreamConfig upstream = definition.upstream();
        final UpstreamConfig slow = new UpstreamConfig(upstream.strategy(), upstream.healthCheck(), new TimeoutConfig(read, null), upstream.targets(), upstream.fallback());
        return new DefaultGatewayRoute(original.uri(), original.predicate(), original.filters(), original.journal(),
                new RouteDefinition(definition.id(), slow, definition.match(), definition.journal(), definition.filters()));
    }

    private GatewayPipeline newHandler()
    {
        // What BlockingGateway builds, and what HotReloadService.onReload does for the routes in
        // service when the pipeline is added
        final ServerConfig config = ServerConfig.standard();
        final GatewayPipeline handler = new GatewayPipeline(config, this.registry, null, new StandardErrorHandler(), this.scheduler,
                route -> new HttpUpstream(UpstreamOptions.of(config, route.routeDefinition().upstream())));
        handler.prepare(this.registry.getRoutes());
        return handler;
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
        return this.configurationManager.build(ConfigurationManager.load(file, RoutesDefinition.class)).routes();
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
