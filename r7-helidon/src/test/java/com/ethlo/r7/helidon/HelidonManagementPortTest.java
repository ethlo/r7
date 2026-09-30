package com.ethlo.r7.helidon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The management port on Helidon: the same endpoint as Undertow's, on a listener of its own.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HelidonManagementPortTest
{
    private Path dir;
    private R7Helidon gateway;
    private int port;

    @BeforeAll
    void start() throws Exception
    {
        this.dir = Files.createTempDirectory("r7-helidon-mgmt-");
        final Path routes = this.dir.resolve("routes.yaml");
        Files.writeString(routes, """
                version: mgmt
                routes:
                  - id: secretive
                    match:
                      - PathPrefix:
                          prefix: /
                    upstream:
                      targets:
                        - url: http://user:url-s3cret-pass@127.0.0.1:1
                """);
        final Path server = this.dir.resolve("server.yaml");
        Files.writeString(server, """
                server:
                  port: %d
                  host: 127.0.0.1
                management:
                  port: %d
                  host: 127.0.0.1
                  request_parse_timeout: 1s
                  idle_timeout: 1s
                storage:
                  work_dir: %s
                """.formatted(freePort(), freePort(), this.dir.resolve("journals").toAbsolutePath()));
        this.gateway = new R7Helidon(routes, server);
        this.port = this.gateway.managementPort();
    }

    @AfterAll
    void stop() throws IOException
    {
        if (this.gateway != null)
        {
            this.gateway.stop();
        }
        try (var paths = Files.walk(this.dir))
        {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void theDashboardIsServedWithItsSecurityHeaders() throws Exception
    {
        final HttpResponse<String> response = get("text/html");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("<script>");
        assertThat(response.headers().firstValue("Content-Security-Policy")).get().asString().contains("script-src 'sha256-");
        assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }

    @Test
    void theJsonHasTheRoutesWithCredentialsMasked() throws Exception
    {
        final HttpResponse<String> response = get("application/json");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("route_configs").contains("secretive").doesNotContain("url-s3cret-pass");
    }

    @Test
    void aHostNameThatWasNotConfiguredIsRefused() throws Exception
    {
        try (Socket socket = new Socket("127.0.0.1", this.port))
        {
            socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: attacker.example:" + this.port + "\r\nAccept: application/json\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            final String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertThat(response).startsWith("HTTP/1.1 421").doesNotContain("route_configs");
        }
    }

    @Test
    void onlyReadsAreAccepted() throws Exception
    {
        final HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/"))
                .POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Allow")).contains("GET, HEAD");
    }

    /**
     * Helidon has no request-head timeout of its own; HeadTimeouts enforces request_parse_timeout.
     */
    @Test
    void anUnfinishedRequestHeadIsClosed() throws IOException
    {
        try (Socket socket = new Socket("127.0.0.1", this.port))
        {
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            socket.setSoTimeout(10_000);
            final InputStream in = socket.getInputStream();
            while (in.read() != -1)
            {
                // drain
            }
        }
        catch (final SocketTimeoutException e)
        {
            throw new AssertionError("The management listener kept an unfinished request open for 10s", e);
        }
    }

    /**
     * A request line trickled without its end never reaches r7's head timer; Helidon counts such
     * a connection as idle, and idle_timeout ends it.
     */
    @Test
    void aTrickledRequestLineIsClosedAsIdle() throws IOException
    {
        try (Socket socket = new Socket("127.0.0.1", this.port))
        {
            socket.getOutputStream().write("GET / HT".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            socket.setSoTimeout(10_000);
            final InputStream in = socket.getInputStream();
            try
            {
                while (in.read() != -1)
                {
                    // drain
                }
            }
            catch (final SocketTimeoutException e)
            {
                throw new AssertionError("A trickled request line held its connection for 10s", e);
            }
            catch (final IOException closed)
            {
                // Reset: closed too.
            }
        }
    }

    private static int freePort() throws IOException
    {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0))
        {
            return socket.getLocalPort();
        }
    }

    private HttpResponse<String> get(final String accept) throws Exception
    {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/")).header("Accept", accept).build(), HttpResponse.BodyHandlers.ofString());
    }
}
