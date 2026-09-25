package com.ethlo.r7.undertow;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.restassured.path.json.JsonPath;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

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
        int bodyBytes = -1;
        for (int attempt = 0; attempt < 20; attempt++)
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
                break;
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
        // Docker gateway images used by jvm-docker/native-docker test modes.
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
                .withCookie("kept_c", equalTo("should_remain")));
        // TODO: //.withoutCookie("removed_c"));
    }
}