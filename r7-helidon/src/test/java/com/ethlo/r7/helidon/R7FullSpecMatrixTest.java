package com.ethlo.r7.helidon;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(OrderAnnotation.class)
public class R7FullSpecMatrixTest extends AbstractR7IntegrationTest
{
    @BeforeAll
    public static void setupTopology()
    {
        UPSTREAM_SERVER.stubFor(get(urlEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("Welcome to wiremock!")));

        startGateway("configs/matrix/routes-matrix.yaml");
    }

    @Test
    @Order(1)
    public void testComplexPredicateAndFilterPipeline()
    {
        given()
                // You can add headers or query params here if needed
                .when()
                .get("/api/v1/")
                .then()
                .statusCode(200)
                .body(containsString("Welcome to wiremock!"))
                .header("X-Powered-By", equalTo("ethlo r7"))
                .header("X-Correlation-Id", notNullValue());
    }

    @Test
    @Order(2)
    public void testPredicateMismatchesThrow404()
    {
        // 1. Invalid Method
        given()
                .when()
                .put("/api/v1/")
                .then()
                .statusCode(404);

        // 2. Invalid Path
        given()
                .when()
                .get("/unmatched-route")
                .then()
                .statusCode(404)
                // On the response: it was once set on the request headers, leaving the 404 untyped
                .header("Content-Type", equalTo("text/plain; charset=utf-8"))
                .header("X-Content-Type-Options", equalTo("nosniff"));
    }

    /**
     * TRACE reflects the request, cookies and credentials included; it is refused before any
     * route is consulted, so it never reaches an upstream that would echo it.
     */
    @Test
    @Order(3)
    public void traceIsRefusedBeforeRouting()
    {
        given()
                .when()
                .request("TRACE", "/api/v1/")
                .then()
                .statusCode(501)
                .header("Content-Type", equalTo("text/plain; charset=utf-8"))
                .header("X-Content-Type-Options", equalTo("nosniff"));
    }

    /**
     * TRACE is refused in any case: an upstream that reads {@code trace} as TRACE would otherwise
     * echo the request back. Pinned when the pipeline moved out of r7-undertow, where the check
     * had relied on Undertow's case-insensitive HttpString. Sent over a raw socket, because
     * RestAssured upper-cases the method and would hide exactly this.
     */
    @Test
    @Order(3)
    public void traceIsRefusedInAnyCase() throws Exception
    {
        for (final String method : new String[]{"trace", "Trace"})
        {
            try (final java.net.Socket socket = new java.net.Socket("localhost", io.restassured.RestAssured.port))
            {
                socket.setSoTimeout(5_000);
                socket.getOutputStream().write((method + " /api/v1/ HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                final String statusLine = new java.io.BufferedReader(new java.io.InputStreamReader(
                        socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII)).readLine();
                org.junit.jupiter.api.Assertions.assertTrue(statusLine != null && statusLine.startsWith("HTTP/1.1 501"),
                        method + " was not refused: " + statusLine);
            }
        }
    }
}