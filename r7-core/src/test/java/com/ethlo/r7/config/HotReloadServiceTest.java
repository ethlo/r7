package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.spi.EngineContext;

/**
 * A generation of routes is prepared before any request can match it, so that what listeners
 * build for it goes live with it, and a generation a listener rejects is never published.
 */
class HotReloadServiceTest
{
    @TempDir
    Path dir;

    private final GatewayScheduler scheduler = new GatewayScheduler(1);
    private final RouteRegistry registry = new RouteRegistry();

    @AfterEach
    void stop()
    {
        this.scheduler.shutdown();
    }

    @Test
    void listenersPrepareTheNewRoutesBeforeTheyArePublishedAndRetireTheOldAfter() throws IOException
    {
        final HotReloadService service = this.start("first");
        final List<GatewayRoute> first = this.registry.getRoutes();
        final Recorder recorder = new Recorder();
        service.onReload(recorder);

        this.write("second");
        service.reloadPipeline(false);

        assertThat(this.routeIds()).containsExactly("second");
        assertThat(recorder.events).containsExactly("prepare [second] while serving [first]", "retire [first] while serving [second]");
        assertThat(recorder.retired).containsExactly(first);
        assertThat(service.status().rejectedAt()).isNull();
    }

    @Test
    void aRejectedGenerationIsNotPublishedAndIsRetiredByTheListenersThatPreparedIt() throws IOException
    {
        final HotReloadService service = this.start("first");
        final Recorder prepared = new Recorder();
        service.onReload(prepared);
        service.onReload(new Recorder()
        {
            @Override
            public void prepare(final List<GatewayRoute> routes)
            {
                throw new IllegalStateException("cannot build upstream");
            }
        });

        this.write("second");
        service.reloadPipeline(false);

        assertThat(this.routeIds()).containsExactly("first");
        assertThat(prepared.events).containsExactly("prepare [second] while serving [first]", "retire [second] while serving [first]");
        assertThat(service.status().rejectedAt()).isNotNull();
    }

    @Test
    void failingToRetireTheOldGenerationDoesNotRejectTheNewOne() throws IOException
    {
        final HotReloadService service = this.start("first");
        service.onReload(new Recorder()
        {
            @Override
            public void retire(final List<GatewayRoute> routes)
            {
                throw new IllegalStateException("cannot stop monitor");
            }
        });

        this.write("second");
        service.reloadPipeline(false);

        assertThat(this.routeIds()).containsExactly("second");
        assertThat(service.status().rejectedAt()).isNull();
    }

    private HotReloadService start(final String routeId) throws IOException
    {
        this.write(routeId);
        return new HotReloadService(this.scheduler, this.dir.resolve("routes.yaml"), new ConfigurationManager(new EngineContext(Map.of())), this.registry);
    }

    private void write(final String routeId) throws IOException
    {
        Files.writeString(this.dir.resolve("routes.yaml"), """
                version: test
                routes:
                  - id: %s
                    match:
                      - PathPrefix:
                          prefix: /%s
                    upstream:
                      targets:
                        - url: http://localhost:1
                """.formatted(routeId, routeId));
    }

    private List<String> routeIds()
    {
        return this.registry.getRoutes().stream().map(GatewayRoute::id).toList();
    }

    private class Recorder implements RouteGenerationListener
    {
        final List<String> events = new ArrayList<>();
        final List<List<GatewayRoute>> retired = new ArrayList<>();

        @Override
        public void prepare(final List<GatewayRoute> routes)
        {
            this.events.add("prepare " + ids(routes) + " while serving " + routeIds());
        }

        @Override
        public void retire(final List<GatewayRoute> routes)
        {
            this.events.add("retire " + ids(routes) + " while serving " + routeIds());
            this.retired.add(routes);
        }

        private List<String> ids(final List<GatewayRoute> routes)
        {
            return routes.stream().map(GatewayRoute::id).toList();
        }
    }
}
