package com.ethlo.r7.server.kit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * WebSocket proxying, checked on the wire: the handshake reaches the upstream, its 101 reaches
 * the client with the headers the client verifies, and from then on the connection is a tunnel -
 * bytes both ways, the upstream's first frames included, until either side closes, which must
 * end the other. The upstreams are {@link ScriptedUpstream}s that answer the handshake and then
 * echo raw bytes: the tunnel does not interpret frames, so the kit does not need them.
 * <p>
 * A server module runs the kit by extending it and implementing {@link #startGateway}. Each case
 * has a route and upstream of its own.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class WebSocketKit
{
    // RFC 6455 §1.3's sample nonce and the accept value that answers it.
    private static final String KEY = "dGhlIHNhbXBsZSBub25jZQ=="; // gitleaks:allow (RFC 6455 sample nonce)
    private static final String ACCEPT = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
    private static final String SWITCH = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + ACCEPT + "\r\n\r\n";

    private final Map<String, ScriptedUpstream> upstreams = new LinkedHashMap<>();
    private final CountDownLatch clientCloseSeenUpstream = new CountDownLatch(1);
    private Path dir;
    private AutoCloseable gateway;
    private int gatewayPort;
    private int managementPort;

    /**
     * Starts the server under test with these configuration files, listening on the data-plane
     * port they name, and returns a handle that stops it.
     */
    protected abstract AutoCloseable startGateway(Path routesYaml, Path serverYaml) throws Exception;

    /**
     * Whether the server has the management port, where the WebSocket gauge is read; a servlet
     * container has none.
     */
    protected boolean hasManagementPort()
    {
        return true;
    }

    @BeforeAll
    void start() throws Exception
    {
        scripts();
        this.dir = Files.createTempDirectory("r7-websocket-kit-");
        this.gatewayPort = freePort();
        this.managementPort = freePort();
        final StringBuilder routes = new StringBuilder("version: websocket-kit\nglobal_filters:\n  - SimpleMetrics\nroutes:\n");
        for (final Map.Entry<String, ScriptedUpstream> e : this.upstreams.entrySet())
        {
            routes.append("""
                      - id: %s
                        match:
                          - PathPrefix:
                              prefix: /%s
                        upstream:
                          timeouts:
                            read: 2s
                          targets:
                            - url: %s
                    """.formatted(e.getKey(), e.getKey(), e.getValue().url()));
        }
        final Path routesFile = this.dir.resolve("routes.yaml");
        Files.writeString(routesFile, routes.toString(), StandardCharsets.UTF_8);
        final Path server = this.dir.resolve("server.yaml");
        Files.writeString(server, """
                server:
                  port: %d
                  host: 127.0.0.1
                management:
                  port: %d
                  host: 127.0.0.1
                storage:
                  work_dir: %s
                """.formatted(this.gatewayPort, this.managementPort, this.dir.resolve("journals").toAbsolutePath()), StandardCharsets.UTF_8);
        this.gateway = startGateway(routesFile, server);
    }

    @AfterAll
    void stop() throws Exception
    {
        if (this.gateway != null)
        {
            this.gateway.close();
        }
        for (final ScriptedUpstream upstream : this.upstreams.values())
        {
            upstream.close();
        }
        if (this.dir != null)
        {
            try (var paths = Files.walk(this.dir))
            {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private void scripts() throws IOException
    {
        // The greeting goes out in the same write as the 101, so it reaches the gateway in the
        // same read as the head - where a relay that reads the head through a buffer has it.
        add("echo", c ->
        {
            final ScriptedUpstream.Request handshake = c.readHeadOnly();
            if (handshake == null)
            {
                return;
            }
            c.write(SWITCH + "hello");
            c.socket().setSoTimeout(0);
            final byte[] buffer = new byte[8192];
            int n;
            while ((n = c.in().read(buffer)) != -1)
            {
                c.out().write(buffer, 0, n);
                c.out().flush();
            }
        });
        add("metered", c ->
        {
            if (c.readHeadOnly() != null)
            {
                c.write(SWITCH);
                c.awaitClose();
            }
        });
        add("upstreamcloses", c ->
        {
            if (c.readHeadOnly() != null)
            {
                c.write(SWITCH + "bye");
            }
        });
        add("clientcloses", c ->
        {
            if (c.readHeadOnly() != null)
            {
                c.write(SWITCH);
                c.socket().setSoTimeout(0);
                try
                {
                    while (c.in().read() != -1)
                    {
                        // Wait for the gateway to close the tunnel.
                    }
                }
                catch (final SocketException e)
                {
                    // Reset: closed as well.
                }
                this.clientCloseSeenUpstream.countDown();
            }
        });
        add("refused", c ->
        {
            while (c.readHeadOnly() != null)
            {
                c.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 2\r\n\r\nno");
            }
        });
        add("unasked", c ->
        {
            if (c.readHeadOnly() != null)
            {
                c.write(SWITCH + "smuggled");
                c.awaitClose();
            }
        });
    }

    private void add(final String name, final ScriptedUpstream.Script script) throws IOException
    {
        this.upstreams.put(name, new ScriptedUpstream(script));
    }

    // ============================================================================================
    // The cases
    // ============================================================================================

    @Test
    void theHandshakeIsRelayedAndTheTunnelCarriesBytesBothWays() throws Exception
    {
        try (Socket socket = handshake("echo"))
        {
            final InputStream in = socket.getInputStream();
            final OutputStream out = socket.getOutputStream();
            final Head head = readHead(in);
            assertThat(head.status).isEqualTo(101);
            assertThat(head.header("Upgrade")).isEqualToIgnoringCase("websocket");
            assertThat(head.header("Connection").toLowerCase(Locale.ROOT)).contains("upgrade");
            assertThat(head.header("Sec-WebSocket-Accept")).isEqualTo(ACCEPT);

            assertThat(readExactly(in, 5)).isEqualTo("hello".getBytes(StandardCharsets.ISO_8859_1));

            out.write("ping".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            assertThat(readExactly(in, 4)).isEqualTo("ping".getBytes(StandardCharsets.ISO_8859_1));

            // More than any buffer on the way, in both directions at once.
            final byte[] bulk = new byte[1 << 20];
            new Random(42).nextBytes(bulk);
            final Thread writer = Thread.ofVirtual().start(() ->
            {
                try
                {
                    out.write(bulk);
                    out.flush();
                }
                catch (final IOException ignored)
                {
                    // The read below fails too.
                }
            });
            assertThat(Arrays.equals(readExactly(in, bulk.length), bulk)).as("the bytes came back as sent").isTrue();
            writer.join(10_000);
        }
    }

    @Test
    void theUpstreamClosingEndsTheTunnel() throws Exception
    {
        try (Socket socket = handshake("upstreamcloses"))
        {
            final InputStream in = socket.getInputStream();
            assertThat(readHead(in).status).isEqualTo(101);
            assertThat(readExactly(in, 3)).isEqualTo("bye".getBytes(StandardCharsets.ISO_8859_1));
            assertThat(readsToEnd(in)).as("the client connection was closed").isTrue();
        }
    }

    @Test
    void theClientClosingEndsTheTunnel() throws Exception
    {
        try (Socket socket = handshake("clientcloses"))
        {
            assertThat(readHead(socket.getInputStream()).status).isEqualTo(101);
        }
        assertThat(this.clientCloseSeenUpstream.await(5, TimeUnit.SECONDS)).as("the upstream connection was closed").isTrue();
    }

    /**
     * The tunnel's end is what ends the exchange for the metrics and the journal, both of which
     * wait for the connection to close: a gauge that stays up means the journal never got the
     * exchange's end either.
     */
    @Test
    void anOpenTunnelCountsAsAnActiveWebSocketUntilItCloses() throws Exception
    {
        org.junit.jupiter.api.Assumptions.assumeTrue(hasManagementPort(), "no management port");
        try (Socket socket = handshake("metered"))
        {
            assertThat(readHead(socket.getInputStream()).status).isEqualTo(101);
            assertThat(awaitActiveWebSockets("metered", 1)).as("active WebSockets while the tunnel is open").isEqualTo(1);
        }
        assertThat(awaitActiveWebSockets("metered", 0)).as("active WebSockets once the client closed").isEqualTo(0);
    }

    /**
     * Polls the management JSON until the route's gauge reads {@code expected}; telemetry is
     * published on a tick, not as it changes. Returns the last value read.
     */
    private long awaitActiveWebSockets(final String route, final long expected) throws Exception
    {
        final java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long active = -1;
        while (System.nanoTime() < deadline)
        {
            final String json = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + this.managementPort + "/"))
                    .header("Accept", "application/json").build(), java.net.http.HttpResponse.BodyHandlers.ofString()).body();
            for (final tools.jackson.databind.JsonNode metrics : new tools.jackson.databind.ObjectMapper().readTree(json).path("route_metrics"))
            {
                if (route.equals(metrics.path("id").asString()))
                {
                    active = metrics.path("request_statistics").path("websocket_active").asLong();
                }
            }
            if (active == expected)
            {
                return active;
            }
            Thread.sleep(200);
        }
        return active;
    }

    @Test
    void aRefusedUpgradeIsAnOrdinaryResponse() throws Exception
    {
        try (Socket socket = handshake("refused"))
        {
            final InputStream in = socket.getInputStream();
            final Head head = readHead(in);
            assertThat(head.status).isEqualTo(403);
            assertThat(head.header("Upgrade")).isNull();
            assertThat(new String(readExactly(in, 2), StandardCharsets.ISO_8859_1)).isEqualTo("no");
        }
    }

    @Test
    void aSwitchTheClientDidNotAskForIsRefused() throws Exception
    {
        try (Socket socket = new Socket("127.0.0.1", this.gatewayPort))
        {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write("GET /unasked HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            final InputStream in = socket.getInputStream();
            final Head head = readHead(in);
            // A gateway error of either kind (Undertow's client answers 503); what matters is
            // that the upstream's bytes never reach a client that is not expecting them.
            assertThat(head.status).isBetween(500, 599);
            final byte[] rest = in.readNBytes(head.contentLength());
            assertThat(new String(rest, StandardCharsets.ISO_8859_1)).doesNotContain("smuggled");
        }
    }

    // ============================================================================================
    // The client
    // ============================================================================================

    private Socket handshake(final String route) throws IOException
    {
        final Socket socket = new Socket("127.0.0.1", this.gatewayPort);
        socket.setSoTimeout(10_000);
        final String request = "GET /" + route + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: Upgrade\r\n"
                + "Upgrade: websocket\r\n"
                + "Sec-WebSocket-Key: " + KEY + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
        return socket;
    }

    private record Head(int status, Map<String, String> headers)
    {
        String header(final String name)
        {
            return this.headers.get(name.toLowerCase(Locale.ROOT));
        }

        int contentLength()
        {
            final String value = header("Content-Length");
            return value == null ? 0 : Integer.parseInt(value);
        }
    }

    /**
     * Reads a response head a byte at a time, so that nothing after it is consumed.
     */
    private static Head readHead(final InputStream in) throws IOException
    {
        final String statusLine = readLine(in);
        assertThat(statusLine).as("a response head").isNotNull();
        final int status = Integer.parseInt(statusLine.substring(9, 12));
        final Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty())
        {
            final int colon = line.indexOf(':');
            headers.merge(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim(), (a, b) -> a + ", " + b);
        }
        return new Head(status, headers);
    }

    private static String readLine(final InputStream in) throws IOException
    {
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1)
        {
            if (b == '\n')
            {
                final String s = line.toString(StandardCharsets.ISO_8859_1);
                return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
            }
            line.write(b);
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
    }

    private static byte[] readExactly(final InputStream in, final int length) throws IOException
    {
        final byte[] bytes = in.readNBytes(length);
        assertThat(bytes.length).as("bytes before the connection ended").isEqualTo(length);
        return bytes;
    }

    /**
     * Whether the connection ends - a close or a reset - before the read times out.
     */
    private static boolean readsToEnd(final InputStream in) throws IOException
    {
        try
        {
            while (in.read() != -1)
            {
                // Nothing more is expected; discard.
            }
            return true;
        }
        catch (final SocketException e)
        {
            return true;
        }
        catch (final java.net.SocketTimeoutException e)
        {
            return false;
        }
    }

    private static int freePort() throws IOException
    {
        try (ServerSocket socket = new ServerSocket(0))
        {
            return socket.getLocalPort();
        }
    }
}
