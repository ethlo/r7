package com.ethlo.r7.helidon;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;

/**
 * A client is free to ask for a protocol upgrade the upstream then refuses - it answers with an
 * ordinary status instead of {@code 101 Switching Protocols}. The gateway must judge "is this a
 * websocket" by that answer, not by the client's ask: the Undertow server r7 used to run on
 * computed the flag from the request's {@code Upgrade}
 * header alone, before the upstream was even proxied to. That treated every refused attempt as a
 * live websocket, which:
 * <ul>
 *   <li>left the exchange's journal entry uncompleted by the normal completion listener, deferred
 *   instead to a listener on the underlying TCP connection's close - which, on a persistent
 *   connection, could be arbitrarily later, or never if the connection is still serving other
 *   requests when the process shuts down;</li>
 *   <li>incremented the "active websocket" gauge ({@code SimpleMetricsFactory}) with no matching
 *   decrement until that same, potentially far-future, connection close.</li>
 * </ul>
 * This reaches the upstream via the {@code e2e-passthrough} route configured with the
 * {@code SimpleMetrics} global filter in {@code configs/e2e/e2e-routes.yaml}.
 */
class WebSocketUpgradeOutcomeTest extends AbstractR7IntegrationTest
{
    private static final String ROUTE_ID = "e2e-passthrough";

    @BeforeAll
    static void setupTopology()
    {
        // The upstream declines the upgrade - a plain 200, not 101 - exactly as a backend with
        // no websocket support of its own would.
        UPSTREAM_SERVER.stubFor(get(urlEqualTo("/ws-refused"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("no upgrade here")));

        startGateway("configs/e2e/e2e-routes.yaml");
    }

    @Test
    void refusedUpgradeDoesNotLeaveTheActiveWebsocketGaugeStuck() throws Exception
    {
        try (Socket socket = new Socket("localhost", RestAssured.port))
        {
            socket.setSoTimeout(5_000);
            final String request = "GET /ws-refused HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" // gitleaks:allow (RFC 6455 sample nonce)
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "\r\n";

            final OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            final String statusLine = readStatusLine(socket.getInputStream());
            assertTrue(statusLine.startsWith("HTTP/1.1 200"), "Expected the upstream's plain 200 to be relayed, got: " + statusLine);

            // The socket is deliberately kept open here - a persistent connection an ordinary
            // client would keep using - so the gauge cannot be relying on it closing.
            //
            // Telemetry is flushed to the readable snapshot on a background 2s tick (see
            // TelemetryFlusher), not synchronously: route_metrics reads empty until the first
            // tick after startup, which a bare "poll until 0" would misread as success before
            // this request was ever accounted for. Instead, wait for the first snapshot that has
            // actually counted this request (total >= 1) and judge that one.
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            Long total = null;
            long websocketActive = -1;
            while (System.nanoTime() < deadline && total == null)
            {
                final String statusJson = fetchStatusJson();
                final Object totalValue = JsonPath.from(statusJson).get(totalPath());
                if (totalValue != null && ((Number) totalValue).longValue() >= 1)
                {
                    total = ((Number) totalValue).longValue();
                    final Object activeValue = JsonPath.from(statusJson).get(websocketActivePath());
                    websocketActive = activeValue == null ? 0L : ((Number) activeValue).longValue();
                }
                else
                {
                    Thread.sleep(250);
                }
            }

            assertTrue(total != null && total >= 1, "Expected the request to have been counted in a telemetry snapshot within the timeout");
            assertEquals(0L, websocketActive, "A refused upgrade must not be counted as an active websocket");
        }
    }

    private static String totalPath()
    {
        return "route_metrics.find { it.id == '" + ROUTE_ID + "' }.request_statistics.total";
    }

    private static String websocketActivePath()
    {
        return "route_metrics.find { it.id == '" + ROUTE_ID + "' }.request_statistics.websocket_active";
    }

    private static String fetchStatusJson()
    {
        return io.restassured.RestAssured.given()
                .accept("application/json")
                .baseUri("http://localhost")
                .port(R7_GATEWAY == null ? 18888 : R7_GATEWAY.getMappedPort(18888))
                .when()
                .get("/")
                .then()
                .statusCode(200)
                .extract()
                .asString();
    }

    private static String readStatusLine(final InputStream in) throws IOException
    {
        final StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) != -1 && b != '\r')
        {
            line.append((char) b);
        }
        return line.toString();
    }
}
