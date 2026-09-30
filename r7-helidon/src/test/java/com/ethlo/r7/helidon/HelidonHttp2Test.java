package com.ethlo.r7.helidon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.ethlo.r7.server.kit.ScriptedUpstream;

/**
 * HTTP/2 (h2c) on Helidon, on only with http.enable_http2 as on Undertow. The upstream hop stays
 * HTTP/1.1, so what matters is that a request arriving over HTTP/2 is proxied, its body intact,
 * and that the response carries nothing HTTP/2 forbids.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HelidonHttp2Test
{
    private Path dir;
    private ScriptedUpstream upstream;
    private R7Helidon withHttp2;
    private R7Helidon withoutHttp2;

    @BeforeAll
    void start() throws Exception
    {
        this.dir = Files.createTempDirectory("r7-helidon-h2-");
        Files.createDirectories(this.dir.resolve("site"));
        Files.writeString(this.dir.resolve("site/hello.txt"), "static over h2");
        // Echoes the body length, with the hop-by-hop headers an HTTP/1.1 upstream sends and an
        // HTTP/2 response must not carry.
        this.upstream = new ScriptedUpstream(c ->
        {
            ScriptedUpstream.Request request;
            while ((request = c.readRequest()) != null)
            {
                final String body = request.method() + " " + request.body().length;
                c.write("HTTP/1.1 200 OK\r\nConnection: keep-alive\r\nKeep-Alive: timeout=5\r\nContent-Length: " + body.length() + "\r\n\r\n" + body);
            }
        });
        this.withHttp2 = start(true);
        this.withoutHttp2 = start(false);
    }

    private R7Helidon start(final boolean http2) throws Exception
    {
        final Path routes = this.dir.resolve("routes-" + http2 + ".yaml");
        Files.writeString(routes, """
                version: h2
                routes:
                  - id: static
                    match:
                      - PathPrefix:
                          prefix: /static/
                    filters:
                      - StripPathPrefix:
                          parts: 1
                      - StaticContent:
                          base_directory: %s
                    upstream: null
                  - id: proxied
                    match:
                      - PathPrefix:
                          prefix: /
                    upstream:
                      targets:
                        - url: %s
                """.formatted(this.dir.resolve("site").toAbsolutePath(), this.upstream.url()));
        final Path server = this.dir.resolve("server-" + http2 + ".yaml");
        Files.writeString(server, """
                server:
                  port: %d
                  host: 127.0.0.1
                management:
                  port: %d
                  host: 127.0.0.1
                http:
                  enable_http2: %s
                storage:
                  work_dir: %s
                """.formatted(freePort(), freePort(), http2, this.dir.resolve("journals-" + http2).toAbsolutePath()));
        return new R7Helidon(routes, server);
    }

    @AfterAll
    void stop() throws IOException
    {
        for (final R7Helidon gateway : new R7Helidon[]{this.withHttp2, this.withoutHttp2})
        {
            if (gateway != null)
            {
                gateway.stop();
            }
        }
        this.upstream.close();
        try (var paths = Files.walk(this.dir))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void http2IsOffUnlessEnabled() throws Exception
    {
        final HttpResponse<String> response = client().send(get(this.withoutHttp2, "/off"), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
    }

    @Test
    void requestsOverHttp2AreProxiedWithTheirBodies() throws Exception
    {
        final HttpClient client = client();
        // The first request upgrades the connection (h2c); what follows is HTTP/2 throughout.
        client.send(get(this.withHttp2, "/warmup"), HttpResponse.BodyHandlers.ofString());

        final HttpResponse<String> get = client.send(get(this.withHttp2, "/a"), HttpResponse.BodyHandlers.ofString());
        assertThat(get.version()).isEqualTo(HttpClient.Version.HTTP_2);
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.body()).isEqualTo("GET 0");
        assertThat(get.headers().firstValue("Connection")).isEmpty();
        assertThat(get.headers().firstValue("Keep-Alive")).isEmpty();

        final byte[] body = new byte[100_000];
        final HttpResponse<String> post = client.send(HttpRequest.newBuilder(uri(this.withHttp2, "/b"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(post.version()).isEqualTo(HttpClient.Version.HTTP_2);
        assertThat(post.body()).isEqualTo("POST 100000");
    }

    @Test
    void staticContentIsServedOverHttp2() throws Exception
    {
        final HttpClient client = client();
        client.send(get(this.withHttp2, "/warmup"), HttpResponse.BodyHandlers.ofString());
        final HttpResponse<String> response = client.send(get(this.withHttp2, "/static/hello.txt"), HttpResponse.BodyHandlers.ofString());
        assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_2);
        assertThat(response.body()).isEqualTo("static over h2");
    }

    private static HttpClient client()
    {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(5)).build();
    }

    private static HttpRequest get(final R7Helidon gateway, final String path)
    {
        return HttpRequest.newBuilder(uri(gateway, path)).timeout(Duration.ofSeconds(10)).build();
    }

    private static URI uri(final R7Helidon gateway, final String path)
    {
        return URI.create("http://127.0.0.1:" + gateway.port() + path);
    }

    private static int freePort() throws IOException
    {
        try (ServerSocket socket = new ServerSocket(0))
        {
            return socket.getLocalPort();
        }
    }
}
