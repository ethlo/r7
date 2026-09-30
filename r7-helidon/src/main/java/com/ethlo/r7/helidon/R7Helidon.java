package com.ethlo.r7.helidon;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.server.blocking.BlockingGateway;
import com.ethlo.r7.server.config.ServerConfig;
import io.helidon.webserver.WebServer;

/**
 * EXPERIMENTAL: r7 on Helidon's Níma web server. The same configuration files, routes, filters,
 * journal and pipeline as the Undertow build; a different HTTP server and upstream client
 * underneath. Not yet included: the management port and dashboard, static content, WebSocket
 * proxying, and https upstreams (design/server-spi.md, step 6).
 */
public final class R7Helidon
{
    private static final Logger logger = LoggerFactory.getLogger(R7Helidon.class);

    private final BlockingGateway gateway;
    private final WebServer server;
    private final AtomicBoolean stopped = new AtomicBoolean();

    public R7Helidon(final Path routesFile, final Path serverFile) throws IOException
    {
        this(new BlockingGateway(routesFile, serverFile));
    }

    private R7Helidon(final BlockingGateway gateway)
    {
        this.gateway = gateway;
        final ServerConfig.ServerCoreConfig core = gateway.serverConfig().server();
        this.server = WebServer.builder()
                .host(core.host())
                .port(core.port())
                .routing(routing -> routing.any((req, res) -> gateway.handle(new HelidonGatewayExchange(gateway.pipeline(), req, res))))
                .build()
                .start();
        logger.info("ethlo r7 (Helidon Níma, experimental) listening on {}:{}", core.host(), this.server.port());
    }

    public int port()
    {
        return this.server.port();
    }

    public void stop()
    {
        if (!this.stopped.compareAndSet(false, true))
        {
            return;
        }
        this.server.stop();
        this.gateway.close();
    }

    public static void main(final String[] args) throws IOException
    {
        final R7Helidon gateway = new R7Helidon(BlockingGateway.fromEnvironment());
        Runtime.getRuntime().addShutdownHook(new Thread(gateway::stop, "r7-shutdown-hook"));
    }
}
