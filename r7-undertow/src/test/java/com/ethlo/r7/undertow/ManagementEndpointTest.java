package com.ethlo.r7.undertow;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.restassured.specification.RequestSpecification;

/**
 * The management endpoint renders route configuration; secrets in it must not be rendered.
 */
public class ManagementEndpointTest extends AbstractR7IntegrationTest
{
    @BeforeAll
    public static void setupTopology()
    {
        startGateway("configs/management/management-routes.yaml");
    }

    private static int managementPort()
    {
        return R7_GATEWAY == null ? 18888 : R7_GATEWAY.getMappedPort(18888);
    }

    private static RequestSpecification management()
    {
        return given()
                .baseUri("http://localhost")
                .port(managementPort());
    }

    /**
     * DNS rebinding: a page on attacker.example that now resolves to this host is same-origin
     * with the dashboard, and says so in Host.
     */
    @Test
    public void aHostNameThatWasNotConfiguredIsRefused()
    {
        management()
                .header("Host", "attacker.example:" + managementPort())
                .accept("application/json")
                .when()
                .get("/")
                .then()
                .statusCode(421)
                .body(not(containsString("route_configs")));

        management()
                .header("Host", "127.0.0.1:" + managementPort())
                .accept("application/json")
                .when()
                .get("/")
                .then()
                .statusCode(200);
    }

    /**
     * A partial request head must not hold its connection - and a file descriptor the data plane
     * shares - open indefinitely; the default request_parse_timeout is 2s.
     */
    @Test
    public void anUnfinishedRequestHeadIsClosed() throws IOException
    {
        try (final Socket socket = new Socket("localhost", managementPort()))
        {
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            socket.setSoTimeout(10_000);
            final InputStream in = socket.getInputStream();
            // Undertow may answer 408 before closing; either way the stream must end
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
     * Past management.max_connections (64 by default) a connection is left in the accept backlog,
     * not accepted, and is served once the count falls.
     */
    @Test
    public void connectionsPastTheCapWaitUntilOthersClose() throws IOException
    {
        // A container's port mapping accepts connections itself, so the cap is only visible in-process
        assumeTrue(R7_GATEWAY == null, "the gateway runs behind a Docker port mapping");
        final List<Socket> held = new ArrayList<>();
        try (final Socket extra = openAll(held))
        {
            extra.getOutputStream().write(("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            extra.setSoTimeout(1_500);
            try
            {
                final int read = extra.getInputStream().read();
                throw new AssertionError("A connection past the cap was served (read " + read + ")");
            }
            catch (final SocketTimeoutException expected)
            {
                // not accepted while the others are held
            }

            closeAll(held);
            extra.setSoTimeout(10_000);
            final String response = new String(extra.getInputStream().readNBytes(12), StandardCharsets.US_ASCII);
            assertThat(response).isEqualTo("HTTP/1.1 200");
        }
        finally
        {
            closeAll(held);
        }
    }

    private static Socket openAll(final List<Socket> held) throws IOException
    {
        for (int i = 0; i < 64; i++)
        {
            held.add(new Socket("localhost", managementPort()));
        }
        return new Socket("localhost", managementPort());
    }

    private static void closeAll(final List<Socket> sockets) throws IOException
    {
        for (final Socket socket : sockets)
        {
            socket.close();
        }
        sockets.clear();
    }

    @Test
    public void statusJsonMasksCredentialsAndInjectedSecrets()
    {
        management()
                .accept("application/json")
                .when()
                .get("/")
                .then()
                .statusCode(200)
                .body(not(containsString("upstream-s3cret-value")))
                .body(not(containsString("header-s3cret-token")))
                .body(not(containsString("cookie-s3cret-value")))
                .body(not(containsString("query-s3cret-value")))
                .body(not(containsString("response-cookie-s3cret")))
                .body(not(containsString("url-s3cret-pass")))
                .body(not(containsString("predicate-s3cret-value")))
                .body(not(containsString("response-header-s3cret")))
                .body(not(containsString("require-s3cret-pattern")))
                .body(containsString("upstream-svc"))
                .body(containsString("application/visible+json"))
                .body(containsString("******"));
    }

    @Test
    public void upstreamTargetHealthIsShownWithoutCredentials() throws Exception
    {
        // No request to the route first: its health monitor exists from the moment routes load
        management()
                .accept("application/json")
                .when()
                .get("/")
                .then()
                .statusCode(200)
                .body("upstream_health.with-secrets", aMapWithSize(1))
                .body(not(containsString("url-s3cret-pass")))
                .body(not(containsString("svc-user")));
    }

    @Test
    public void requestsNoRouteMatchesAreCounted() throws Exception
    {
        final long before = management().accept("application/json").get("/").then().extract().jsonPath().getLong("unrouted_requests");

        sendGet("/no-route-matches-this");

        management()
                .accept("application/json")
                .when()
                .get("/")
                .then()
                .statusCode(200)
                .body("unrouted_requests", equalTo((int) before + 1))
                .body("route_source.loaded_at", notNullValue())
                .body("route_source.rejected_at", nullValue())
                .body("route_configs[0].order", equalTo(1));
    }

    @Test
    public void onlyReadsAreAccepted()
    {
        management()
                .when()
                .post("/")
                .then()
                .statusCode(405)
                .header("Allow", equalTo("GET, HEAD"))
                .header("X-Content-Type-Options", equalTo("nosniff"))
                .header("X-Frame-Options", equalTo("DENY"))
                .header("Cache-Control", equalTo("no-store"))
                .header("Referrer-Policy", equalTo("no-referrer"));
    }

    @Test
    public void responsesCarrySecurityHeaders()
    {
        management()
                .when()
                .get("/")
                .then()
                .statusCode(200)
                .header("X-Content-Type-Options", equalTo("nosniff"))
                .header("X-Frame-Options", equalTo("DENY"))
                .header("Cache-Control", equalTo("no-store"))
                .header("Referrer-Policy", equalTo("no-referrer"))
                .header("Content-Type", equalTo("text/html; charset=utf-8"))
                .header("Content-Security-Policy", containsString("script-src 'sha256-"))
                .header("Content-Security-Policy", containsString("frame-ancestors 'none'"))
                .header("Content-Security-Policy", containsString("base-uri 'none'"))
                .header("Content-Security-Policy", not(containsString("unsafe-inline' 'sha")));
    }
}
