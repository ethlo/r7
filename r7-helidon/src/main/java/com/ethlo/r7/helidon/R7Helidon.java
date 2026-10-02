package com.ethlo.r7.helidon;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import com.ethlo.r7.logging.LogbackConfiguration;
import com.ethlo.r7.server.blocking.BlockingGateway;
import com.ethlo.r7.server.config.ServerConfig;
import com.ethlo.r7.status.ManagementEndpoint;
import com.ethlo.r7.status.VersionProvider;
import com.ethlo.r7.util.SystemUtil;
import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.webserver.ProtocolConfigs;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http1.Http1Config;
import io.helidon.webserver.http1.Http1ConnectionSelector;
import io.helidon.webserver.http1.Http1ConnectionSelectorConfig;
import io.helidon.webserver.http2.Http2Config;
import io.helidon.webserver.http2.Http2ConnectionProvider;
import io.helidon.webserver.http2.Http2UpgradeProvider;
import io.helidon.webserver.spi.ProtocolConfig;
import io.helidon.webserver.spi.ServerConnectionSelector;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

/**
 * The r7 gateway on Helidon's Níma web server: the data plane, proxied by r7's own upstream
 * client (r7-upstream), and the management port with the dashboard. Every request runs on the
 * virtual thread of its connection. WebSocket handshakes are taken off Helidon's routing by
 * {@link WebSocketUpgrader} and tunnelled once the upstream switches.
 */
public final class R7Helidon
{
    private static final Logger logger = LoggerFactory.getLogger(R7Helidon.class);
    private static final String MANAGEMENT_SOCKET = "management";
    private static final String DATA_SOCKET = "@default";

    private final BlockingGateway gateway;
    private final HeadTimeouts dataHeadTimeouts;
    private final HeadTimeouts managementHeadTimeouts;
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
        final ServerConfig.LimitsConfig limits = gateway.serverConfig().limits();
        final int maxHeadBytes = Math.toIntExact(limits.maxHeaderSize().bytes());
        this.dataHeadTimeouts = new HeadTimeouts("data", gateway.serverConfig().http().requestParseTimeout());
        this.managementHeadTimeouts = new HeadTimeouts("management", management.requestParseTimeout());
        // Protocols by configuration, not by what is on the classpath: HTTP/2 (h2c, the listener
        // is plaintext) only with http.enable_http2 - it adds a second protocol parser to the
        // attack surface. The upstream hop stays HTTP/1.1.
        // request_parse_timeout is enforced by r7, which Helidon cannot do (HeadTimeouts).
        final List<ProtocolConfig> protocols = new ArrayList<>();
        // max_header_size bounds the whole head; Helidon bounds the request line and the header
        // fields separately, so each gets the limit and BlockingGateway checks the sum.
        protocols.add(Http1Config.builder().addReceiveListener(this.dataHeadTimeouts).maxPrologueLength(maxHeadBytes).maxHeadersSize(maxHeadBytes).build());
        if (gateway.serverConfig().http().enableHttp2())
        {
            protocols.add(Http2Config.builder().maxHeaderListSize(maxHeadBytes).build());
        }
        this.server = WebServer.builder()
                .host(core.host())
                .port(core.port())
                // Past max_connections the listener stops accepting and new connections wait in
                // the backlog; an idle connection, a quiet WebSocket included, is closed after
                // idle_timeout, checked every second so that it holds to about that.
                .maxTcpConnections(core.maxConnections())
                .backlog(core.backlog())
                .idleConnectionTimeout(core.idleTimeout())
                .idleConnectionPeriod(Duration.ofSeconds(1))
                .connectionOptions(socket -> socket.tcpNoDelay(true))
                .protocolsDiscoverServices(false)
                .protocols(protocols)
                .connectionSelectors(countingSelectors(protocols, gateway))
                // Refused as the body arrives, before r7 sees the request; BlockingGateway holds a
                // server without this setting (a servlet container) to the same limit.
                .maxPayloadSize(limits.maxEntitySize().bytes())
                .routing(routing -> routing.any((req, res) -> gateway.handle(new HelidonGatewayExchange(gateway.pipeline(), req, res))))
                // The management port: its own listener, with its own connection cap and idle
                // timeout - it shares the process's descriptors with the data plane, and a client
                // holding connections open must not starve the latter.
                .putSocket(MANAGEMENT_SOCKET, socket -> socket
                        .protocolsDiscoverServices(false)
                        .addProtocol(Http1Config.builder().addReceiveListener(this.managementHeadTimeouts).build())
                        .host(management.host())
                        .port(management.port())
                        .maxTcpConnections(management.maxConnections())
                        .idleConnectionTimeout(management.idleTimeout())
                        // Helidon checks every two minutes by default, which would let an idle
                        // connection - or one trickling its request line, which Helidon counts as
                        // idle - outlive idle_timeout by that much.
                        .idleConnectionPeriod(Duration.ofSeconds(1))
                        .routing(routing -> routing.any((req, res) -> serveManagement(endpoint, req, res))))
                .build()
                .start();
        logger.info("🚀 ethlo r7 Gateway - version {}, started in {}ms", VersionProvider.getVersion(), SystemUtil.getUptime().toMillis());
        logger.info("Gateway listening on {}:{}, management on {}:{}", core.host(), this.server.port(),
                management.host(), this.server.port(MANAGEMENT_SOCKET));
    }

    /**
     * The data plane's protocol selectors, built as Helidon would build them from these protocols
     * and wrapped to count the connections each accepts (CountingSelector).
     */
    private static List<ServerConnectionSelector> countingSelectors(final List<ProtocolConfig> protocols, final BlockingGateway gateway)
    {
        final ProtocolConfigs configs = ProtocolConfigs.create(protocols);
        final List<ServerConnectionSelector> selectors = new ArrayList<>();
        for (final ProtocolConfig protocol : protocols)
        {
            final ServerConnectionSelector selector = switch (protocol)
            {
                case Http1Config http1 -> http1Selector(http1, protocols, configs, gateway);
                case Http2Config http2 -> new Http2ConnectionProvider().create(DATA_SOCKET, http2, configs);
                default -> throw new IllegalStateException("No selector for " + protocol.type());
            };
            selectors.add(new CountingSelector(selector, gateway.statistics()));
        }
        return selectors;
    }

    /**
     * HTTP/1.1 with its upgraders named here rather than found on the class path, as
     * Http1ConnectionProvider would: WebSocket always, h2c only when HTTP/2 is configured.
     */
    private static ServerConnectionSelector http1Selector(final Http1Config http1, final List<ProtocolConfig> protocols, final ProtocolConfigs configs,
                                                          final BlockingGateway gateway)
    {
        final Http1ConnectionSelectorConfig.Builder builder = Http1ConnectionSelector.builder()
                .config(http1)
                .addUpgrader(new WebSocketUpgrader(gateway));
        for (final ProtocolConfig protocol : protocols)
        {
            if (protocol instanceof Http2Config http2)
            {
                builder.addUpgrader(new Http2UpgradeProvider().create(http2, configs));
            }
        }
        return builder.build();
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
        this.dataHeadTimeouts.stop();
        this.managementHeadTimeouts.stop();
        this.gateway.close();
    }

    /**
     * The JDK's I/O pollers run by default as virtual threads on the same carriers as the
     * requests. When the carriers are saturated a poller can wait hundreds of milliseconds for
     * one, and every connection registered with it stalls until it runs: at saturation this
     * produced outliers of 0.5-2 s (p99 8.6 ms) where Undertow's worst was 40 ms. Per-carrier
     * pollers (mode 3) poll as part of each carrier's own scheduling, and the same load gives a
     * worst case of 16-20 ms and p99 4.4 ms (design/history/upstream-client.md). JDK 25 has no mode 3, and is
     * left on its default. Internal and undocumented, so
     * an explicit -Djdk.pollerMode still wins, and a JDK that drops it falls back to its default.
     * Set before anything opens a socket: the poller reads it once, when it starts.
     */
    static void preferPerCarrierPollers()
    {
        // Only where mode 3 exists: JDK 25's poller refuses "3" and fails to start. There the
        // JDK default stays - measured on 25 at saturation, platform-thread pollers (mode 1) end
        // the stalls but raise p99 from 7 ms to 15-50 ms, as they compete with the carriers.
        if (System.getProperty("jdk.pollerMode") == null && Runtime.version().feature() >= 27)
        {
            System.setProperty("jdk.pollerMode", "3");
        }
    }

    public static void main(final String[] args) throws IOException
    {
        preferPerCarrierPollers();
        LogbackConfiguration.configure();

        // Helidon's System.Logger ends in java.util.logging in the repackaged jar; route that into
        // logback too. The configuration's LevelChangePropagator keeps JUL's levels in step, so a
        // disabled record is dropped before the bridge builds an event for it.
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        final R7Helidon gateway = new R7Helidon(BlockingGateway.fromEnvironment());
        Runtime.getRuntime().addShutdownHook(new Thread(gateway::stop, "r7-shutdown-hook"));
    }
}
