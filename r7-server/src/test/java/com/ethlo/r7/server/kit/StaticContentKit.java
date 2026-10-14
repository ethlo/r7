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
        write("sub/data.txt", "data");
        write("sub/.hidden", "hidden");
        write("web/index.html", "<h1>welcome</h1>");
        write("page.html", "<p>html</p>");
        final Path outside = Files.createDirectories(this.dir.resolve("outside"));
        Files.writeString(outside.resolve("content.txt"), "symlinked-content");
        Files.createSymbolicLink(this.site.resolve("linked"), outside);
        Files.createDirectories(this.dir.resolve("swap/current"));
        Files.createDirectories(this.dir.resolve("layer-gone"));
        final Path override = Files.createDirectories(this.dir.resolve("override"));
        Files.writeString(override.resolve("test.txt"), "override");
        Files.createDirectories(override.resolve("web"));
        Files.createDirectories(override.resolve("empty"));

        this.gatewayPort = freePort();
        final Path routes = this.dir.resolve("routes.yaml");
        Files.writeString(routes, """
                version: static-kit
                routes:
                %s%s%s%s%s%s%s
                """.formatted(
                route("static", this.site, ""),
                route("static-hidden", this.site, "serve_hidden_files: true"),
                route("static-follow", this.site, "follow_symlinks: true"),
                route("static-swap", this.dir.resolve("swap/current"), ""),
                filteredRoute(this.site),
                route("static-layers", this.dir.resolve("layer-gone").toAbsolutePath() + ", " + override.toAbsolutePath() + ", " + this.site.toAbsolutePath(), ""),
                route("static-app", this.site.resolve("web"), "fallback: index.html")),
                StandardCharsets.UTF_8);
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
        return route(prefix, base.toAbsolutePath().toString(), option);
    }

    private static String route(final String prefix, final String directories, final String option)
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
                          directories: [%s]
                          %s
                """.formatted(prefix, prefix, directories, option);
    }

    /**
     * Static content behind response filters: they run once, at commit, on the answer static
     * content made, so they see its status and have the last word on its headers.
     */
    private static String filteredRoute(final Path base)
    {
        return """
                  - id: static-filtered
                    match:
                      - PathPrefix:
                          prefix: /static-filtered/
                    filters:
                      - AddResponseHeader:
                          name: X-Once
                          value: "1"
                      - RemoveResponseHeader:
                          name: Accept-Ranges
                      - StripPathPrefix:
                          parts: 1
                      - StaticContent:
                          directories: [%s]
                """.formatted(base.toAbsolutePath());
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
    void responseFiltersRunOnceOnWhatStaticContentAnswered() throws Exception
    {
        final HttpResponse<String> found = get("/static-filtered/test.txt");
        assertThat(found.statusCode()).isEqualTo(200);
        assertThat(found.body()).isEqualTo(TEXT);
        assertThat(found.headers().allValues("X-Once")).containsExactly("1");
        // Set by static content, removed by a response filter: the filter has the last word.
        assertThat(found.headers().firstValue("Accept-Ranges")).isEmpty();

        final HttpResponse<String> missing = get("/static-filtered/no-such-file.txt");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.headers().allValues("X-Once")).containsExactly("1");
        assertThat(missing.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
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
    void aDirectoryIsServedByItsWelcomeFileElseForbidden() throws Exception
    {
        assertThat(get("/static/web/").body()).contains("welcome");
        assertThat(get("/static/sub/").statusCode()).isEqualTo(403);
    }

    @Test
    void aDirectoryWithoutItsTrailingSlashIsRedirectedToIt() throws Exception
    {
        final HttpResponse<String> response = get("/static/web");
        assertThat(response.statusCode()).isBetween(301, 308);
        assertThat(response.headers().firstValue("Location")).get().asString().endsWith("/static/web/");
    }

    /**
     * Several directories: the first that can answer wins, file by file, and one that goes
     * missing is skipped rather than masking the rest.
     */
    @Test
    void theFirstDirectoryThatCanAnswerWins() throws Exception
    {
        // A file in a higher directory hides the lower one's.
        assertThat(get("/static-layers/test.txt").body()).isEqualTo("override");
        // Its neighbours still come from below.
        assertThat(get("/static-layers/page.html").body()).isEqualTo("<p>html</p>");
        assertThat(get("/static-layers/sub/data.txt").body()).isEqualTo("data");
        // A directory without a welcome file does not hide one that has it.
        assertThat(get("/static-layers/web/").body()).contains("welcome");
        // No welcome file anywhere: 403; nothing anywhere: 404.
        assertThat(get("/static-layers/empty/").statusCode()).isEqualTo(403);
        assertThat(get("/static-layers/no-such-file.txt").statusCode()).isEqualTo(404);
        // The hidden-file rule holds whichever directory has the file.
        assertThat(get("/static-layers/.env").statusCode()).isEqualTo(404);

        // A directory gone at runtime is skipped.
        Files.delete(this.dir.resolve("layer-gone"));
        assertThat(get("/static-layers/test.txt").body()).isEqualTo("override");
    }

    /**
     * A single-page app: a path no directory has gets the fallback file with 200, so the
     * browser's router takes it; files, hidden files and directories answer as without it.
     */
    @Test
    void aPathNoDirectoryHasGetsTheFallbackFile() throws Exception
    {
        final HttpResponse<String> route = get("/static-app/orders/42");
        assertThat(route.statusCode()).isEqualTo(200);
        assertThat(route.body()).contains("welcome");
        assertThat(route.headers().firstValue("Content-Type")).get().asString().startsWith("text/html");
        assertThat(get("/static-app/index.html").statusCode()).isEqualTo(200);
        assertThat(get("/static-app/.env").statusCode()).isEqualTo(404);
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
