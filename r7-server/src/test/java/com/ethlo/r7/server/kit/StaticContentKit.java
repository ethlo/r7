package com.ethlo.r7.server.kit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * What the {@code StaticContent} filter serves, checked the same way on every server: each
 * serves it with r7's {@code StaticFiles}, and this kit pins what matters - hidden files,
 * symbolic links, directory handling, a replaced base directory, and the caching and range
 * headers a browser relies on.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class StaticContentKit
{
    private static final String TEXT = "Static content served successfully!";

    private Path dir;
    private Path site;
    private AutoCloseable gateway;
    private int gatewayPort;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * Starts the server under test with these configuration files, listening on the data-plane
     * port they name, and returns a handle that stops it.
     */
    protected abstract AutoCloseable startGateway(Path routesYaml, Path serverYaml) throws Exception;

    @BeforeAll
    void start() throws Exception
    {
        this.dir = Files.createTempDirectory("r7-static-kit-");
        this.site = Files.createDirectories(this.dir.resolve("site"));
        write("test.txt", TEXT);
        write("large.bin", "0123456789abcdef".repeat(64 * 1024));
        write(".env", "SECRET=1");
        write(".well-known/security.txt", "Contact: mailto:security@example.com");
        write("sub/data.txt", "listing-data");
        write("sub/.hidden", "hidden");
        write("web/index.html", "<h1>welcome</h1>");
        write("page.html", "<p>html</p>");
        final Path outside = Files.createDirectories(this.dir.resolve("outside"));
        Files.writeString(outside.resolve("content.txt"), "symlinked-content");
        Files.createSymbolicLink(this.site.resolve("linked"), outside);
        Files.createDirectories(this.dir.resolve("swap/current"));

        this.gatewayPort = freePort();
        final Path routes = this.dir.resolve("routes.yaml");
        Files.writeString(routes, """
                version: static-kit
                routes:
                %s%s%s%s%s
                """.formatted(
                route("static", this.site, ""),
                route("static-hidden", this.site, "serve_hidden_files: true"),
                route("static-listing", this.site, "list_directory: true"),
                route("static-follow", this.site, "follow_symlinks: true"),
                route("static-swap", this.dir.resolve("swap/current"), "")), StandardCharsets.UTF_8);
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
                """.formatted(this.gatewayPort, freePort(), this.dir.resolve("journals").toAbsolutePath()), StandardCharsets.UTF_8);
        this.gateway = startGateway(routes, server);
    }

    private static String route(final String prefix, final Path base, final String option)
    {
        return """
                  - id: %s
                    match:
                      - PathPrefix:
                          prefix: /%s/
                    filters:
                      - StripPathPrefix:
                          parts: 1
                      - StaticContent:
                          base_directory: %s
                          %s
                    upstream: null
                """.formatted(prefix, prefix, base.toAbsolutePath(), option);
    }

    private void write(final String relative, final String content) throws IOException
    {
        final Path file = this.site.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.ISO_8859_1);
    }

    @AfterAll
    void stop() throws Exception
    {
        if (this.gateway != null)
        {
            this.gateway.close();
        }
        if (this.dir != null)
        {
            try (var paths = Files.walk(this.dir))
            {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    // ============================================================================================

    @Test
    void aFileIsServedWithItsTypeValidatorsAndNosniff() throws Exception
    {
        final HttpResponse<String> response = get("/static/test.txt");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(TEXT);
        assertThat(response.headers().firstValue("Content-Type")).get().asString().startsWith("text/plain");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        // An ETag is optional; Last-Modified is not.
        assertThat(response.headers().firstValue("Last-Modified")).isPresent();
        assertThat(get("/static/page.html").headers().firstValue("Content-Type")).get().asString().startsWith("text/html");
    }

    @Test
    void aLargeFileArrivesWhole() throws Exception
    {
        final HttpResponse<byte[]> response = this.client.send(request("/static/large.bin").build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(Files.readAllBytes(this.site.resolve("large.bin")));
    }

    @Test
    void aMissingFileIs404WithNosniff() throws Exception
    {
        final HttpResponse<String> response = get("/static/no-such-file.txt");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    }

    @Test
    void aDotfileIsNotServedButWellKnownIs() throws Exception
    {
        final HttpResponse<String> hidden = get("/static/.env");
        assertThat(hidden.statusCode()).isEqualTo(404);
        assertThat(hidden.body()).doesNotContain("SECRET");
        assertThat(get("/static/sub/.hidden").statusCode()).isEqualTo(404);
        assertThat(get("/static/.well-known/security.txt").statusCode()).isEqualTo(200);
    }

    @Test
    void aDotfileIsServedWhenTheRouteOptsIn() throws Exception
    {
        assertThat(get("/static-hidden/.env").body()).isEqualTo("SECRET=1");
    }

    @Test
    void aSymlinkUnderTheBaseIsFollowedOnlyWhenTheRouteOptsIn() throws Exception
    {
        assertThat(get("/static/linked/content.txt").statusCode()).isEqualTo(404);
        final HttpResponse<String> followed = get("/static-follow/linked/content.txt");
        assertThat(followed.statusCode()).isEqualTo(200);
        assertThat(followed.body()).isEqualTo("symlinked-content");
    }

    @Test
    void aDirectoryIsServedByItsWelcomeFileElseForbiddenUnlessListed() throws Exception
    {
        assertThat(get("/static/web/").body()).contains("welcome");
        assertThat(get("/static/sub/").statusCode()).isEqualTo(403);
        final HttpResponse<String> listing = get("/static-listing/sub/");
        assertThat(listing.statusCode()).isEqualTo(200);
        assertThat(listing.body()).contains("data.txt").doesNotContain(".hidden");
    }

    @Test
    void aDirectoryWithoutItsTrailingSlashIsRedirectedToIt() throws Exception
    {
        final HttpResponse<String> response = get("/static/web");
        assertThat(response.statusCode()).isBetween(301, 308);
        assertThat(response.headers().firstValue("Location")).get().asString().endsWith("/static/web/");
    }

    @Test
    void anUnchangedFileIsNotSentAgain() throws Exception
    {
        final HttpResponse<String> first = get("/static/test.txt");
        final String lastModified = first.headers().firstValue("Last-Modified").orElseThrow();
        assertThat(send(request("/static/test.txt").header("If-Modified-Since", lastModified)).statusCode()).isEqualTo(304);
        final String etag = first.headers().firstValue("ETag").orElse(null);
        if (etag != null)
        {
            assertThat(send(request("/static/test.txt").header("If-None-Match", etag)).statusCode()).isEqualTo(304);
            assertThat(send(request("/static/test.txt").header("If-None-Match", "\"other\"")).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void aByteRangeIsServedPartially() throws Exception
    {
        final HttpResponse<String> response = send(request("/static/test.txt").header("Range", "bytes=0-5"));
        assertThat(response.statusCode()).isEqualTo(206);
        assertThat(response.body()).isEqualTo("Static");
        assertThat(response.headers().firstValue("Content-Range")).contains("bytes 0-5/" + TEXT.length());
        assertThat(send(request("/static/test.txt").header("Range", "bytes=1000-")).statusCode()).isEqualTo(416);
    }

    @Test
    void headSendsTheHeadersOnly() throws Exception
    {
        final HttpResponse<String> response = send(request("/static/test.txt").method("HEAD", HttpRequest.BodyPublishers.noBody()));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Content-Length")).contains(Integer.toString(TEXT.length()));
    }

    @Test
    void onlyReadsAreServed() throws Exception
    {
        assertThat(send(request("/static/test.txt").POST(HttpRequest.BodyPublishers.ofString("x"))).statusCode()).isEqualTo(405);
    }

    @Test
    void aReplacedBaseDirectoryIsServedFromItsNewContent() throws Exception
    {
        final Path root = this.dir.resolve("swap");
        final Path current = root.resolve("current");

        // Removed: a clean 404 while it is missing, not a 500 or a hang.
        Files.delete(current);
        assertThat(get("/static-swap/content.txt").statusCode()).isEqualTo(404);

        // Recreated in place.
        Files.createDirectories(current);
        Files.writeString(current.resolve("content.txt"), "v2-content");
        assertThat(get("/static-swap/content.txt").body()).isEqualTo("v2-content");

        // Swapped for a symbolic link to a new release: the base itself may be a link.
        Files.delete(current.resolve("content.txt"));
        Files.delete(current);
        final Path v3 = Files.createDirectories(root.resolve("v3"));
        Files.writeString(v3.resolve("content.txt"), "v3-content");
        Files.createSymbolicLink(current, Path.of("v3"));
        assertThat(get("/static-swap/content.txt").body()).isEqualTo("v3-content");
    }

    // ============================================================================================

    private HttpRequest.Builder request(final String path)
    {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.gatewayPort + path)).timeout(Duration.ofSeconds(10));
    }

    private HttpResponse<String> get(final String path) throws Exception
    {
        return send(request(path));
    }

    private HttpResponse<String> send(final HttpRequest.Builder builder) throws Exception
    {
        return this.client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.ISO_8859_1));
    }

    private static int freePort() throws IOException
    {
        try (ServerSocket socket = new ServerSocket(0))
        {
            return socket.getLocalPort();
        }
    }
}
