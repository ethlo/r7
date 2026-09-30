package com.ethlo.r7.helidon;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.server.blocking.BlockingGateway;
import com.ethlo.r7.server.config.ServerConfig;
import com.ethlo.r7.status.ManagementEndpoint;
import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http1.Http1Config;
import io.helidon.webserver.http2.Http2Config;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

/**
 * EXPERIMENTAL: r7 on Helidon's Níma web server. The same configuration files, routes, filters,
 * journal and pipeline as the Undertow build; a different HTTP server and upstream client
 * underneath, and the management port with the same dashboard. Not yet included: static
 * content, WebSocket proxying, and https upstreams (design/server-spi.md, step 6).
 */
public final class R7Helidon
{
    private static final Logger logger = LoggerFactory.getLogger(R7Helidon.class);
    private static final String MANAGEMENT_SOCKET = "management";

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
        final ServerConfig.ManagementConfig management = gateway.serverConfig().management();
        final ManagementEndpoint endpoint = gateway.managementEndpoint();
        final boolean http2 = gateway.serverConfig().http().enableHttp2();
        this.server = WebServer.builder()
                .host(core.host())
                .port(core.port())
                // Protocols by configuration, not by what is on the classpath: HTTP/2 (h2c, the
                // listener is plaintext) only with http.enable_http2, as on Undertow - it adds a
                // second protocol parser to the attack surface. The upstream hop stays HTTP/1.1.
                .protocolsDiscoverServices(false)
                .addProtocol(Http1Config.create())
                .update(builder ->
                {
                    if (http2)
                    {
                        builder.addProtocol(Http2Config.create());
                    }
                })
                .routing(routing -> routing.any((req, res) -> gateway.handle(new HelidonGatewayExchange(gateway.pipeline(), req, res))))
                // The management port: its own listener, with its own connection cap and idle
                // timeout, as on Undertow - it shares the process's descriptors with the data
                // plane, and a client holding connections open must not starve the latter.
                .putSocket(MANAGEMENT_SOCKET, socket -> socket
                        .protocolsDiscoverServices(false)
                        .addProtocol(Http1Config.create())
                        .host(management.host())
                        .port(management.port())
                        .maxTcpConnections(management.maxConnections())
                        // Not request_parse_timeout: Helidon has no request-head timeout, and
                        // neither its idle sweep nor socket read timeouts end a connection
                        // stalled in a partial head (HelidonManagementPortTest).
                        .idleConnectionTimeout(management.idleTimeout())
                        .routing(routing -> routing.any((req, res) -> serveManagement(endpoint, req, res))))
                .build()
                .start();
        logger.info("ethlo r7 (Helidon Níma, experimental) listening on {}:{}, management on {}:{}", core.host(), this.server.port(),
                management.host(), this.server.port(MANAGEMENT_SOCKET));
    }

    public int port()
    {
        return this.server.port();
    }

    public int managementPort()
    {
        return this.server.port(MANAGEMENT_SOCKET);
    }

    private static void serveManagement(final ManagementEndpoint endpoint, final ServerRequest req, final ServerResponse res)
    {
        final ManagementEndpoint.Response response = endpoint.handle(req.prologue().method().text(),
                req.headers().first(HeaderNames.HOST).orElse(null), req.headers().first(HeaderNames.ACCEPT).orElse(null));
        res.status(Status.create(response.status()));
        for (final Map.Entry<String, String> header : response.headers().entrySet())
        {
            res.header(HeaderNames.create(header.getKey()), header.getValue());
        }
        if (response.body().length == 0)
        {
            res.send();
        }
        else
        {
            res.send(response.body());
        }
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

    /**
     * The JDK's I/O pollers run by default as virtual threads on the same carriers as the
     * requests. When the carriers are saturated a poller can wait hundreds of milliseconds for
     * one, and every connection registered with it stalls until it runs: at saturation this
     * produced outliers of 0.5-2 s (p99 8.6 ms) where Undertow's worst was 40 ms. Per-carrier
     * pollers (mode 3) poll as part of each carrier's own scheduling, and the same load gives a
     * worst case of 16-20 ms and p99 4.4 ms (design/upstream.md). Internal and undocumented, so
     * an explicit -Djdk.pollerMode still wins, and a JDK that drops it falls back to its default.
     * Set before anything opens a socket: the poller reads it once, when it starts.
     */
    static void preferPerCarrierPollers()
    {
        if (System.getProperty("jdk.pollerMode") == null)
        {
            System.setProperty("jdk.pollerMode", "3");
        }
    }

    public static void main(final String[] args) throws IOException
    {
        preferPerCarrierPollers();
        final R7Helidon gateway = new R7Helidon(BlockingGateway.fromEnvironment());
        Runtime.getRuntime().addShutdownHook(new Thread(gateway::stop, "r7-shutdown-hook"));
    }
}
