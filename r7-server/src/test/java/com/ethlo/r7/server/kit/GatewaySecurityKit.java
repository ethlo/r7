package com.ethlo.r7.server.kit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The request-hygiene guarantees every r7 server must give, checked on the wire.
 * <p>
 * Each case sends raw bytes to a running gateway and asserts two things: what the client got
 * back, and what a {@link RecordingUpstream} received - byte-exact, not as some HTTP library
 * re-reads it. That makes the kit black-box: it holds for Undertow, for Helidon Níma, and for a
 * servlet container hosting r7, whatever each server's parser rejects or normalises before the
 * pipeline sees the request. A server where the parser already refuses something the pipeline
 * would have refused passes just the same; a server that lets something through to the upstream
 * fails, whichever layer was supposed to stop it.
 * <p>
 * A server module runs the kit by extending it and implementing {@link #startGateway}.
 * The kit also states the proxy contract a new upstream layer must meet - that the gateway
 * writes {@code X-Forwarded-For} from the connection's peer, for instance - because those are
 * the headers it checks for.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class GatewaySecurityKit
{
    private static final Duration SETTLE = Duration.ofMillis(300);
    private static final Duration ARRIVAL = Duration.ofSeconds(5);
    private static final int MAX_HEAD_BYTES = 4096;
    private static final int MAX_HEADER_COUNT = 20;
    private static final int MAX_ENTITY_BYTES = 64 * 1024;

    private Path dir;

    private RecordingUpstream upstream;
    private AutoCloseable gateway;
    private int gatewayPort;

    /**
     * Starts the server under test with these configuration files, listening on the data-plane
     * port they name, and returns a handle that stops it.
     */
    protected abstract AutoCloseable startGateway(Path routesYaml, Path serverYaml) throws Exception;

    /**
     * Extra top-level YAML for server.yaml, such as a proxy client selection.
     */
    protected String serverYamlExtra()
    {
        return "";
    }

    @BeforeAll
    void start() throws Exception
    {
        this.dir = Files.createTempDirectory("r7-security-kit-");
        this.upstream = new RecordingUpstream();
        this.gatewayPort = freePort();
        final Path routes = this.dir.resolve("routes.yaml");
        Files.writeString(routes, """
                version: security-kit
                routes:
                  - id: kit
                    match:
                      - PathPrefix:
                          prefix: /kit
                    upstream:
                      targets:
                        - url: %s
                """.formatted(this.upstream.url()), StandardCharsets.UTF_8);
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
                limits:
                  max_header_size: %dB
                  max_header_count: %d
                  max_entity_size: %dB
                %s
                """.formatted(this.gatewayPort, freePort(), this.dir.resolve("journals").toAbsolutePath(),
                MAX_HEAD_BYTES, MAX_HEADER_COUNT, MAX_ENTITY_BYTES, serverYamlExtra()), StandardCharsets.UTF_8);
        this.gateway = startGateway(routes, server);
    }

    @AfterAll
    void stop() throws Exception
    {
        if (this.gateway != null)
        {
            this.gateway.close();
        }
        if (this.upstream != null)
        {
            this.upstream.close();
        }
        if (this.dir != null)
        {
            try (var paths = Files.walk(this.dir))
            {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @BeforeEach
    void forgetEarlierRequests()
    {
        this.upstream.clear();
    }

    // ============================================================================================
    // Refused before anything reaches the upstream
    // ============================================================================================

    /**
     * A Transfer-Encoding the upstream could parse differently from the gateway would let the two
     * disagree on where the body ends: request smuggling. Only absent or exactly "chunked" passes.
     */
    @Test
    void transferEncodingOtherThanChunkedIsRefused() throws Exception
    {
        for (final String te : new String[]{"chunked, identity", "gzip, chunked", "identity", "chunked\r\nTransfer-Encoding: chunked"})
        {
            final int status = send("POST /kit/te HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: " + te + "\r\n\r\n0\r\n\r\n");
            assertRefused(status, "Transfer-Encoding: " + te);
        }
    }

    /**
     * A body framed by both Content-Length and Transfer-Encoding is the classic smuggling vector
     * (CL.TE / TE.CL). Whatever the server decides, the upstream must never see both.
     */
    @Test
    void conflictingFramingNeverReachesTheUpstreamAsSent() throws Exception
    {
        final int status = send("POST /kit/clte HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n");
        final RecordingUpstream.Received received = this.upstream.next(SETTLE);
        if (received != null)
        {
            assertThat(received.has("Content-Length") && received.has("Transfer-Encoding"))
                    .as("upstream received both framing headers (client got %d):%n%s", status, received)
                    .isFalse();
        }
    }

    /**
     * TRACE echoes the request, credentials included; nothing behind a gateway needs it. In any
     * case: an upstream that reads "trace" as TRACE would echo it just the same.
     */
    @Test
    void traceIsRefusedInAnyCase() throws Exception
    {
        for (final String method : new String[]{"TRACE", "trace", "Trace"})
        {
            final int status = send(method + " /kit/trace HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertThat(status).as("%s answered", method).isEqualTo(501);
            assertThat(this.upstream.next(SETTLE)).as("%s reached the upstream", method).isNull();
        }
    }

    /**
     * A path the upstream could resolve differently from how route predicates read it would let a
     * request match one route and reach another. Refused, whether by the server's parser or the
     * pipeline.
     */
    @Test
    void ambiguousPathsAreRefused() throws Exception
    {
        for (final String path : new String[]{
                "/kit/../admin", "/kit/./x", "/kit/%2e%2e/admin", "/kit/%2E%2E/admin", "/kit/.%2e/admin",
                "/kit/a%2fb", "/kit/a%5cb", "/kit/%252e%252e/admin", "/kit/..;/admin", "/kit/x%00y", "/kit/x%0ay"})
        {
            final int status = send("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertRefused(status, path);
        }
    }

    // ============================================================================================
    // Forwarded, without what must not pass
    // ============================================================================================

    /**
     * Hop-by-hop headers describe the client's connection, not the gateway's; a header the client
     * names in Connection is hop-by-hop by declaration - except framing and Host, which removing
     * would itself be a smuggling primitive.
     */
    @Test
    void hopByHopHeadersStopAtTheGateway() throws Exception
    {
        final RecordingUpstream.Received received = forward("GET /kit/hop HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: keep-alive, X-Secret, Host\r\n"
                + "Keep-Alive: timeout=5\r\n"
                + "Proxy-Connection: keep-alive\r\n"
                + "Proxy-Authorization: Basic c2VjcmV0\r\n"
                + "X-Secret: s3cr3t\r\n"
                + "TE: gzip\r\n"
                + "Upgrade: h2c\r\n"
                + "X-Kept: yes\r\n"
                + "\r\n");

        for (final String name : new String[]{"X-Secret", "Keep-Alive", "Proxy-Connection", "Proxy-Authorization", "TE", "Upgrade"})
        {
            assertThat(received.has(name)).as("%s reached the upstream:%n%s", name, received).isFalse();
        }
        assertThat(received.values("Host")).as("Host, which the client listed in Connection").isNotEmpty();
        assertThat(received.values("X-Kept")).containsExactly("yes");
    }

    /**
     * "TE: trailers" is how a client says it accepts trailers (gRPC depends on it), so it passes.
     */
    @Test
    void teTrailersPasses() throws Exception
    {
        final RecordingUpstream.Received received = forward("GET /kit/te-trailers HTTP/1.1\r\nHost: localhost\r\nTE: trailers\r\n\r\n");
        assertThat(received.values("TE")).containsExactly("trailers");
    }

    /**
     * Client-address and original-request claims are only believed from a trusted proxy, and the
     * kit's client is not one: the upstream must see none of them, in any spelling a CGI-style
     * backend would read as the same header, and the gateway's own X-Forwarded-For must name the
     * real peer.
     */
    @Test
    void forwardingClaimsFromAnUntrustedClientAreReplaced() throws Exception
    {
        final RecordingUpstream.Received received = forward("GET /kit/forwarded HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "X-Forwarded-For: 6.6.6.6\r\n"
                + "X_Forwarded_For: 6.6.6.6\r\n"
                + "X-Forwarded_User: admin\r\n"
                + "X-Real-IP: 6.6.6.6\r\n"
                + "X-Client-IP: 6.6.6.6\r\n"
                + "True-Client-IP: 6.6.6.6\r\n"
                + "X-Cluster-Client-IP: 6.6.6.6\r\n"
                + "Forwarded: for=6.6.6.6\r\n"
                + "X-Original-URL: /admin\r\n"
                + "X-Rewrite-URL: /admin\r\n"
                + "\r\n");

        for (final String name : new String[]{"X_Forwarded_For", "X-Forwarded_User", "X-Real-IP", "X-Client-IP", "True-Client-IP",
                "X-Cluster-Client-IP", "Forwarded", "X-Original-URL", "X-Rewrite-URL"})
        {
            assertThat(received.has(name)).as("%s reached the upstream:%n%s", name, received).isFalse();
        }
        assertThat(String.join(",", received.values("X-Forwarded-For")))
                .as("X-Forwarded-For the upstream received")
                .doesNotContain("6.6.6.6")
                .contains("127.0.0.1");
        // Proxy contract rather than hygiene: the upstream learns the Host the client asked for.
        assertThat(received.values("X-Forwarded-Host")).containsExactly("localhost");
    }

    /**
     * An upgrade the upstream must answer keeps Upgrade, and Connection carries only the token
     * that asks for it.
     */
    @Test
    void aWebSocketUpgradeKeepsOnlyWhatTheUpgradeNeeds() throws Exception
    {
        final RecordingUpstream.Received received = forward("GET /kit/ws HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: Upgrade, X-Drop\r\n"
                + "Upgrade: websocket\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                // The sample nonce from RFC 6455 section 1.3, not a credential.
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" // gitleaks:allow
                + "X-Drop: 1\r\n"
                + "\r\n");

        assertThat(received.values("Upgrade")).containsExactly("websocket");
        assertThat(received.values("Connection")).containsExactly("Upgrade");
        assertThat(received.has("X-Drop")).isFalse();
    }

    // ============================================================================================
    // Bodies arrive whole, framed once
    // ============================================================================================

    @Test
    void aContentLengthBodyArrivesExactly() throws Exception
    {
        final RecordingUpstream.Received received = forward("POST /kit/cl HTTP/1.1\r\nHost: localhost\r\nContent-Length: 11\r\n\r\nhello world");
        assertThat(received.bodyText()).isEqualTo("hello world");
    }

    @Test
    void aChunkedBodyArrivesWhole() throws Exception
    {
        final RecordingUpstream.Received received = forward("POST /kit/chunked HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n");
        assertThat(received.bodyText()).isEqualTo("hello world");
        assertThat(received.has("Content-Length") && received.has("Transfer-Encoding")).isFalse();
    }

    // ============================================================================================
    // limits.*: the same bounds whichever server parses the request
    // ============================================================================================

    @Test
    void aHeadWithinTheLimitsPasses() throws Exception
    {
        // Exactly max_header_count headers: Host, the padding, and the rest.
        final String request = "GET /kit/limits HTTP/1.1\r\nHost: localhost\r\n" + headers(MAX_HEADER_COUNT - 2, 10)
                + "X-Pad: " + "p".repeat(MAX_HEAD_BYTES / 2) + "\r\n\r\n";
        forward(request);
    }

    @Test
    void aHeadLargerThanMaxHeaderSizeIsRefused() throws Exception
    {
        final int status = send("GET /kit/limits HTTP/1.1\r\nHost: localhost\r\nX-Pad: " + "p".repeat(MAX_HEAD_BYTES) + "\r\n\r\n");
        assertRefused(status, "a head over max_header_size");
    }

    /**
     * Split over many headers, each small: the limit is on the head, not per header.
     */
    @Test
    void aHeadLargerThanMaxHeaderSizeInSmallHeadersIsRefused() throws Exception
    {
        final int status = send("GET /kit/limits HTTP/1.1\r\nHost: localhost\r\n" + headers(MAX_HEADER_COUNT - 2, MAX_HEAD_BYTES / (MAX_HEADER_COUNT - 4)) + "\r\n");
        assertRefused(status, "many small headers over max_header_size");
    }

    @Test
    void moreHeadersThanMaxHeaderCountAreRefused() throws Exception
    {
        final int status = send("GET /kit/limits HTTP/1.1\r\nHost: localhost\r\n" + headers(MAX_HEADER_COUNT + 5, 4) + "\r\n");
        assertRefused(status, "more headers than max_header_count");
    }

    @Test
    void aDeclaredBodyLargerThanMaxEntitySizeIsRefusedUnsent() throws Exception
    {
        final int status = send("POST /kit/limits HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + (MAX_ENTITY_BYTES + 1) + "\r\n\r\n"
                + "b".repeat(MAX_ENTITY_BYTES + 1));
        assertRefused(status, "a Content-Length over max_entity_size");
    }

    /**
     * A chunked body is only known to be too large once it has streamed past the limit, by which
     * time the head may be upstream: what must never happen is the upstream receiving it as a
     * complete request.
     */
    @Test
    void aChunkedBodyLargerThanMaxEntitySizeNeverArrivesComplete() throws Exception
    {
        final String chunk = "c".repeat(8192);
        final StringBuilder request = new StringBuilder("POST /kit/limits HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n");
        for (int sent = 0; sent <= MAX_ENTITY_BYTES; sent += chunk.length())
        {
            request.append(Integer.toHexString(chunk.length())).append("\r\n").append(chunk).append("\r\n");
        }
        request.append("0\r\n\r\n");
        final int status = send(request.toString());
        assertThat(status == -1 || status >= 400).as("a chunked body over max_entity_size answered %d", status).isTrue();
        // RecordingUpstream records a chunked request only once its last chunk has arrived.
        assertThat(this.upstream.next(SETTLE)).as("the upstream received the oversized body as a complete request").isNull();
    }

    @Test
    void aBodyWithinMaxEntitySizeArrives() throws Exception
    {
        final String body = "b".repeat(MAX_ENTITY_BYTES);
        final RecordingUpstream.Received received = forward("POST /kit/limits HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + body.length() + "\r\n\r\n" + body);
        assertThat(received.bodyText()).isEqualTo(body);
    }

    private static String headers(final int count, final int valueLength)
    {
        final StringBuilder headers = new StringBuilder();
        for (int i = 0; i < count; i++)
        {
            headers.append("X-Kit-").append(i).append(": ").append("v".repeat(valueLength)).append("\r\n");
        }
        return headers.toString();
    }

    // ============================================================================================

    private RecordingUpstream.Received forward(final String request) throws Exception
    {
        final int status = send(request);
        final RecordingUpstream.Received received = this.upstream.next(ARRIVAL);
        assertThat(received).as("nothing reached the upstream; the client got %d", status).isNotNull();
        return received;
    }

    private void assertRefused(final int status, final String what) throws InterruptedException
    {
        final RecordingUpstream.Received received = this.upstream.next(SETTLE);
        assertThat(received).as("%s reached the upstream (client got %d):%n%s", what, status, received).isNull();
        // A status of -1 is a server that closed the connection without answering: refused too.
        assertThat(status == -1 || (status >= 400 && status <= 599)).as("%s answered %d", what, status).isTrue();
    }

    /**
     * Sends {@code request} on a fresh connection and returns the response status, or -1 if the
     * server closed the connection without answering (which is a refusal too).
     */
    private int send(final String request) throws IOException
    {
        try (Socket socket = new Socket("127.0.0.1", this.gatewayPort))
        {
            socket.setSoTimeout(10_000);
            final OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            final InputStream in = socket.getInputStream();
            final StringBuilder statusLine = new StringBuilder();
            int b;
            while ((b = in.read()) != -1 && b != '\r' && b != '\n')
            {
                statusLine.append((char) b);
            }
            final String[] parts = statusLine.toString().split(" ", 3);
            return parts.length >= 2 ? Integer.parseInt(parts[1]) : -1;
        }
        catch (final java.net.SocketException e)
        {
            return -1;
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
