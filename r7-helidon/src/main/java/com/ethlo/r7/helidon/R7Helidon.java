package com.ethlo.r7.helidon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
    /**
     * Set only by Dockerfile.jvm's AOT training run: start, serve a few requests, stop and exit.
     */
    static final String AOT_TRAINING_PROPERTY = "r7.aot.training";

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
                .maxConnections(core.maxConnections())
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
                        .maxConnections(management.maxConnections())
                        .idleConnectionTimeout(management.idleTimeout())
                        // Helidon checks every two minutes by default, which would let an idle
                        // connection - or one trickling its request line, which Helidon counts as
                        // idle - outlive idle_timeout by that much.
                        .idleConnectionPeriod(Duration.ofSeconds(1))
                        .routing(routing -> routing.any((req, res) -> serveManagement(endpoint, req, res))))
                .build()
                .start();
        endpoint.start();
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
        final ManagementEndpoint.Response response = endpoint.handle(req.prologue().method().text(), req.path().path(),
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

    static final String POLLER_MODE_ENV = "R7_POLLER_MODE";

    /**
     * Platform-thread I/O pollers (mode 1) unless {@code R7_POLLER_MODE} or an explicit
     * -Djdk.pollerMode says otherwise; the explicit property wins.
     * <p>
     * With virtual-thread pollers (2, the JDK default) or per-carrier pollers (3), a woken
     * connection is queued on its carrier, while a new connection's thread, started by Helidon's
     * platform-thread acceptor, waits in the scheduler's shared queue. ForkJoinPool drains a
     * carrier's own queue before it looks there, so at saturation new connections waited 5-20 s
     * for their first read (design/history/upstream-client.md, "New connections starved at
     * saturation"). Platform-thread pollers wake every thread through the shared queue, in order.
     * Mode 3 has the shorter tail for connections already open, which is why it stays selectable.
     * Set before anything opens a socket: the poller reads it once, when it starts.
     *
     * @param pollerModeEnv the value of {@code R7_POLLER_MODE}, or {@code null}
     */
    static void choosePollerMode(final String pollerModeEnv)
    {
        if (System.getProperty("jdk.pollerMode") != null)
        {
            return;
        }
        final String mode = pollerModeEnv == null || pollerModeEnv.isBlank() ? "1" : pollerModeEnv.strip();
        if (!mode.equals("1") && !mode.equals("2") && !mode.equals("3"))
        {
            throw new IllegalArgumentException(POLLER_MODE_ENV + " must be 1, 2 or 3, was '" + pollerModeEnv + "'");
        }
        System.setProperty("jdk.pollerMode", mode);
    }

    public static void main(final String[] args) throws IOException
    {
        choosePollerMode(System.getenv(POLLER_MODE_ENV));
        LogbackConfiguration.configure();

        // Helidon's System.Logger ends in java.util.logging in the repackaged jar; route that into
        // logback too. The configuration's LevelChangePropagator keeps JUL's levels in step, so a
        // disabled record is dropped before the bridge builds an event for it.
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        final R7Helidon gateway = new R7Helidon(BlockingGateway.fromEnvironment());
        if (Boolean.getBoolean(AOT_TRAINING_PROPERTY))
        {
            train(gateway);
            // Halt, not stop() or exit: the JVM writes the AOT cache as it halts, and nothing
            // outlives this throwaway build stage. Stopping Helidon's listeners (which exit's
            // shutdown hooks also do) closes their sockets to wake a thread blocked in accept;
            // under emulation, as in the arm64 half of the image build, that wake-up never
            // comes, and the build hung.
            Runtime.getRuntime().halt(0);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(gateway::stop, "r7-shutdown-hook"));
    }

    /**
     * The AOT training run (Dockerfile.jvm): requests through the data plane and to the
     * management port, so that the classes a request needs are in the cache along with those of
     * startup, and the first requests after a cold start do not load them. A failed request fails
     * the image build rather than leaving a cache that covers less than it should.
     */
    static void train(final R7Helidon gateway) throws IOException
    {
        for (int i = 0; i < 20; i++)
        {
            trainingRequest(gateway.port(), "GET /training/" + i + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            trainingRequest(gateway.port(), "POST /training HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                    + "Content-Length: 17\r\nConnection: close\r\n\r\n{\"training\":true}");
            trainingRequest(gateway.managementPort(), "GET / HTTP/1.1\r\nHost: localhost\r\nAccept: application/json\r\nConnection: close\r\n\r\n");
        }
    }

    private static void trainingRequest(final int port, final String request) throws IOException
    {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port))
        {
            socket.setSoTimeout(10_000);
            final OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            final InputStream in = socket.getInputStream();
            final byte[] head = in.readNBytes(12);
            if (head.length < 12 || !new String(head, StandardCharsets.US_ASCII).startsWith("HTTP/1.1 "))
            {
                throw new IOException("AOT training request to port " + port + " got no HTTP response");
            }
            in.transferTo(OutputStream.nullOutputStream());
        }
    }
}
