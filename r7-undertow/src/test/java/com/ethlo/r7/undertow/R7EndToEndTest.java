package com.ethlo.r7.undertow;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.restassured.path.json.JsonPath;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;


import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import io.restassured.RestAssured;

import org.testcontainers.images.builder.Transferable;

public class R7EndToEndTest extends AbstractR7IntegrationTest
{
    @BeforeAll
    public static void setupTopology()
    {
        UPSTREAM_SERVER.stubFor(get(urlEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("Welcome to wiremock e2e!")));

        startGateway("configs/e2e/e2e-routes.yaml");
    }

    @Test
    public void testProxyRoutingToUpstream()
    {
        given()
                .when()
                .get("/")
                .then()
                .statusCode(200)
                .body(containsString("Welcome to wiremock e2e!"));
    }

    @Test
    public void testStaticContentServing()
    {
        given()
                .when()
                .get("/static/test.txt")
                .then()
                .statusCode(200)
                .body(containsString("Static content served successfully!"));
    }

    @Test
    public void testStaticContentBodyBytesAreCounted() throws Exception
    {
        // Undertow's ResourceHandler streams static files >= 1024 bytes via zero-copy
        // sendfile (StreamSinkConduit#transferFrom), bypassing write(). If
        // CountingSinkConduit only overrides write(), the file body never reaches the
        // byte counters and route_metrics.traffic_flow.egress.body_bytes reports header
        // size only. Below 1024 bytes Undertow buffers through write() instead, so the
        // fixture must exceed that threshold to exercise the sendfile path.
        final String largeContent = "x".repeat(4096);
        if (R7_GATEWAY != null)
        {
            R7_GATEWAY.copyFileToContainer(Transferable.of(largeContent), "/tmp/large-test.txt");
        }
        else
        {
            java.nio.file.Files.writeString(Path.of("/tmp/large-test.txt"), largeContent, StandardCharsets.UTF_8);
        }

        given()
                .when()
                .get("/static/large-test.txt")
                .then()
                .statusCode(200)
                .body(Matchers.equalTo(largeContent));

        final int fileSize = largeContent.getBytes(StandardCharsets.UTF_8).length;

        // Telemetry is flushed to the readable snapshot on a background 2s tick, not synchronously.
        // The first non-null value is not enough: static-test's counters are cumulative across
        // the class, so an earlier request (e.g. testStaticContentServing's /static/test.txt)
        // can leave a stale snapshot that predates this response. Keep polling until the
        // snapshot has caught up, or the deadline passes and the assertion reports the last value.
        int bodyBytes = -1;
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline)
        {
            final String statusJson = given()
                    .accept("application/json")
                    .baseUri("http://localhost")
                    .port(R7_GATEWAY == null ? 18888 : R7_GATEWAY.getMappedPort(18888))
                    .when()
                    .get("/")
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString();

            final Object value = JsonPath.from(statusJson)
                    .get("route_metrics.find { it.id == 'static-test' }.traffic_flow.egress.body_bytes");
            if (value != null)
            {
                bodyBytes = ((Number) value).intValue();
                if (bodyBytes >= fileSize)
                {
                    break;
                }
            }
            Thread.sleep(250);
        }

        Assertions.assertTrue(bodyBytes >= fileSize,
                "Expected egress body_bytes (" + bodyBytes + ") to include the served file size (" + fileSize + ")");
    }

    @Test
    public void testFollowSymlinksDisabledByDefaultBlocksNestedSymlinks() throws Exception
    {
        // symlinkAtomic needs host filesystem access, unavailable against the distroless
        // Docker gateway image used by the jvm-docker test mode.
        Assumptions.assumeTrue(R7_GATEWAY == null, "requires host filesystem access for symlink creation");
        writeContainerOrHostFile("/tmp/static-symlink-target/content.txt", "symlinked-content");
        symlinkAtomic("/tmp/static-symlink-test/linked", "/tmp/static-symlink-target");

        // The symlink lives *inside* the base directory (not at the base directory level
        // itself), so without follow_symlinks it must not be resolved
        given()
                .when()
                .get("/static-symlink-blocked/linked/content.txt")
                .then()
                .statusCode(404);
    }

    @Test
    public void testFollowSymlinksEnabledServesNestedSymlinks() throws Exception
    {
        Assumptions.assumeTrue(R7_GATEWAY == null, "requires host filesystem access for symlink creation");
        writeContainerOrHostFile("/tmp/static-symlink-target/content.txt", "symlinked-content");
        symlinkAtomic("/tmp/static-symlink-test/linked", "/tmp/static-symlink-target");

        given()
                .when()
                .get("/static-symlink-followed/linked/content.txt")
                .then()
                .statusCode(200)
                .body(Matchers.equalTo("symlinked-content"));
    }

    @Test
    public void testListDirectoryDisabledByDefaultForbidsListing() throws Exception
    {
        writeContainerOrHostFile("/tmp/static-listing-test/sub/data.txt", "listing-data");

        given()
                .when()
                .get("/static-listing-off/sub/")
                .then()
                .statusCode(403);
    }

    @Test
    public void testListDirectoryEnabledRendersListing() throws Exception
    {
        writeContainerOrHostFile("/tmp/static-listing-test/sub/data.txt", "listing-data");

        given()
                .when()
                .get("/static-listing-on/sub/")
                .then()
                .statusCode(200)
                .body(containsString("data.txt"));
    }

    @Test
    public void testStaticContentSurvivesDirectoryReplacement() throws Exception
    {
        Assumptions.assumeTrue(R7_GATEWAY == null, "requires host filesystem access for directory deletion/symlink swap");
        final String root = "/tmp/static-swap-test";
        final String current = root + "/current";

        // The directory is removed before the very first request for this route, so the
        // gateway has never built (and cached) a resource handler for it yet. It must fail
        // fast with a clean 404 while the directory is momentarily missing, not a 500 or a hang.
        deleteContainerOrHostPath(current);

        given()
                .when()
                .get("/static-swap/content.txt")
                .then()
                .statusCode(404);

        // The site builder then publishes its output by replacing the whole directory in
        // place (recreating a plain directory at the exact same path)
        writeContainerOrHostFile(current + "/content.txt", "v2-content");

        given()
                .when()
                .get("/static-swap/content.txt")
                .then()
                .statusCode(200)
                .body(Matchers.equalTo("v2-content"));

        // ... and again via an atomic symlink swap to a new release directory
        deleteContainerOrHostPath(current);
        writeContainerOrHostFile(root + "/v3/content.txt", "v3-content");
        symlinkAtomic(current, "v3");

        given()
                .when()
                .get("/static-swap/content.txt")
                .then()
                .statusCode(200)
                .body(Matchers.equalTo("v3-content"));
    }

    @Test
    public void testQueryParameterModification()
    {
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo("/query-modify"))
                .willReturn(aResponse().withStatus(200)));

        given()
                .queryParam("removed_q", "should_be_stripped")
                .queryParam("kept_q", "should_remain")
                .when()
                .get("/query-modify")
                .then()
                .statusCode(200);

        UPSTREAM_SERVER.verify(getRequestedFor(urlPathEqualTo("/query-modify"))
                .withQueryParam("added_q", equalTo("injected_value"))
                .withQueryParam("kept_q", equalTo("should_remain"))
                .withoutQueryParam("removed_q"));
    }

    @Test
    public void testCookieModification()
    {
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo("/cookie-modify"))
                .willReturn(aResponse().withStatus(200)));

        given()
                .cookie("removed_c", "should_be_stripped")
                .cookie("kept_c", "should_remain")
                .when()
                .get("/cookie-modify")
                .then()
                .statusCode(200);

        UPSTREAM_SERVER.verify(getRequestedFor(urlPathEqualTo("/cookie-modify"))
                .withCookie("added_c", equalTo("injected_cookie"))
                .withCookie("kept_c", equalTo("should_remain"))
                // M7: RemoveRequestCookie used to set the cookie's value to Java null and then
                // string-concatenate it while rebuilding the header, sending "removed_c=null"
                // upstream instead of actually removing the cookie.
                .withCookie("removed_c", absent()));
    }

    /**
     * When the primary upstream is down, the request is handed to the fallback route before the
     * primary's upstream filters run: the fallback's upstream must see neither the primary's
     * injected credentials nor miss the fallback route's own request filters.
     */
    @Test
    public void fallbackRunsItsOwnPipelineAndNeverCarriesThePrimarysCredentials() throws InterruptedException
    {
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo("/fallback-primary/charge")).willReturn(aResponse().withStatus(200)));

        // The health check needs a probe or two to mark the closed port down; until then the
        // request is proxied to the dead primary and fails.
        int status = -1;
        for (int attempt = 0; attempt < 50 && status != 200; attempt++)
        {
            status = given().when().get("/fallback-primary/charge").then().extract().statusCode();
            if (status != 200)
            {
                Thread.sleep(200);
            }
        }
        Assertions.assertEquals(200, status, "request never reached the fallback route");

        UPSTREAM_SERVER.verify(getRequestedFor(urlPathEqualTo("/fallback-primary/charge"))
                .withHeader("X-Fallback-Route", equalTo("applied"))
                .withoutHeader("Authorization"));
    }

    /**
     * A rewrite hands the proxy a decoded path; the client's percent-encoding must survive into
     * the upstream request line, or %3F turns into a query string, %25 into a second decode and
     * %20 into a space that splits the request line.
     */
    @Test
    public void strippedPathKeepsTheClientsPercentEncoding() throws IOException
    {
        UPSTREAM_SERVER.stubFor(get(urlPathMatching("/users.*")).willReturn(aResponse().withStatus(200)));

        final String statusLine = sendRaw("GET /strip-encoding/users%3Fadmin=true%23x%20y%25z HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        Assertions.assertTrue(statusLine.startsWith("HTTP/1.1 200"), statusLine);
        UPSTREAM_SERVER.verify(getRequestedFor(urlEqualTo("/users%3Fadmin=true%23x%20y%25z")));
        UPSTREAM_SERVER.verify(0, getRequestedFor(urlPathEqualTo("/users")).withQueryParam("admin", equalTo("true")));
    }

    /**
     * Absolute-form request (RFC 9112 §3.2.2) followed by a rewrite: the rewritten URI is a bare
     * path, so ProxyHandler must not go looking for a scheme and host inside it.
     */
    @Test
    public void strippedAbsoluteFormPathIsNotCutAtADoubleSlash() throws IOException
    {
        UPSTREAM_SERVER.stubFor(get(urlPathMatching("/a//.*")).willReturn(aResponse().withStatus(200)));

        final String statusLine = sendRaw("GET http://localhost:" + RestAssured.port + "/strip-encoding/a//b/c HTTP/1.1\r\nHost: localhost:" + RestAssured.port + "\r\nConnection: close\r\n\r\n");

        Assertions.assertTrue(statusLine.startsWith("HTTP/1.1 200"), statusLine);
        UPSTREAM_SERVER.verify(getRequestedFor(urlEqualTo("/a//b/c")));
    }

    /**
     * Sent over a raw socket: HTTP clients normalise dot-segments before sending, which would
     * hide exactly the requests this guards against. The e2e-passthrough route matches every
     * path, so anything that got past the guard would be proxied to the upstream.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/public/../admin/secret",
            "/public/%2e%2e/admin/secret",
            "/public/..%2fadmin/secret",
            "/public/..;/admin/secret",
            "/public/%252e%252e/admin/secret",
            "/public/..%5cadmin/secret",
            "/public/a%00b"
    })
    public void ambiguousPathsAreRejectedBeforeRouting(final String rawPath) throws IOException
    {
        // Only this request's traffic may count: anything at all reaching the upstream means the
        // guard let it through, whichever route it then matched.
        UPSTREAM_SERVER.resetRequests();

        final String statusLine = sendRaw("GET " + rawPath + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        Assertions.assertTrue(statusLine.startsWith("HTTP/1.1 400"), "Expected 400 for " + rawPath + " but got: " + statusLine);
        UPSTREAM_SERVER.verify(0, anyRequestedFor(anyUrl()));
    }

    /**
     * Undertow accepts "chunked, identity" and the proxy used to copy it upstream verbatim, where
     * a backend reading the list differently would frame the body differently (RFC 9112 §6.3).
     */
    @Test
    public void nonCanonicalTransferEncodingIsRejectedBeforeRouting() throws IOException
    {
        UPSTREAM_SERVER.resetRequests();

        final String statusLine = sendRaw("POST /te-test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nTransfer-Encoding: chunked, identity\r\n\r\n3\r\nabc\r\n0\r\n\r\n");

        Assertions.assertTrue(statusLine.startsWith("HTTP/1.1 400"), statusLine);
        UPSTREAM_SERVER.verify(0, anyRequestedFor(anyUrl()));
    }

    /**
     * The framing of a request with a non-canonical Transfer-Encoding is suspect, so the
     * connection must not carry another request - even when its path is also ambiguous, which
     * is rejected by a different check. No "Connection: close": the server has to close it.
     */
    @Test
    public void nonCanonicalTransferEncodingClosesThePersistentConnection() throws IOException
    {
        UPSTREAM_SERVER.resetRequests();

        final String responses = sendRawUntilClosed(
                "POST /public/../admin HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked, identity\r\n\r\n3\r\nabc\r\n0\r\n\r\n"
                        + "GET /after HTTP/1.1\r\nHost: localhost\r\n\r\n");

        Assertions.assertTrue(responses.startsWith("HTTP/1.1 400"), responses);
        Assertions.assertEquals(1, responses.split("HTTP/1\\.1 ", -1).length - 1, "a second request was served on the same connection: " + responses);
        UPSTREAM_SERVER.verify(0, anyRequestedFor(anyUrl()));
    }

    /**
     * @return everything the server sent before closing the connection; fails if it is still
     * open after the timeout
     */
    private static String sendRawUntilClosed(final String request) throws IOException
    {
        try (final Socket socket = new Socket("localhost", RestAssured.port))
        {
            socket.setSoTimeout(5_000);
            final OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            final InputStream in = socket.getInputStream();
            final StringBuilder all = new StringBuilder();
            int b;
            try
            {
                while ((b = in.read()) != -1)
                {
                    all.append((char) b);
                }
            }
            catch (final java.net.SocketTimeoutException stillOpen)
            {
                Assertions.fail("the server kept the connection open: " + all);
            }
            return all.toString();
        }
    }

    private static String sendRaw(final String request) throws IOException
    {
        try (final Socket socket = new Socket("localhost", RestAssured.port))
        {
            socket.setSoTimeout(5_000);
            final OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            final InputStream in = socket.getInputStream();
            final StringBuilder line = new StringBuilder();
            int b;
            while ((b = in.read()) != -1 && b != '\r')
            {
                line.append((char) b);
            }
            return line.toString();
        }
    }
}
