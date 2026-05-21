package com.ethlo.r7.undertow;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class R7HotReloadTest extends AbstractR7IntegrationTest
{
    @BeforeAll
    public static void setupTopology()
    {
        UPSTREAM_SERVER.stubFor(get(urlEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("Welcome to wiremock!")));

        startGateway("configs/hot-reload/routes-v1.yaml");
    }

    @Test
    public void testHotReloadWithoutDroppingTraffic() throws Exception
    {
        // 1. Verify v1 is active and v2 is NOT active initially
        given().when().get("/v1/").then().statusCode(200);
        given().when().get("/v2/").then().statusCode(404);

        final AtomicBoolean isTrafficRunning = new AtomicBoolean(true);
        final AtomicInteger successfulRequests = new AtomicInteger(0);
        final AtomicReference<Throwable> backgroundError = new AtomicReference<>();

        final URI v1Uri = URI.create(getProxyBaseUrl() + "/v1/");
        final java.net.URL targetUrl = v1Uri.toURL();

        // 2. Start a continuous stream of background traffic against /v1 using a Virtual Thread
        final Thread trafficThread = Thread.ofVirtual().start(() -> {
            while (isTrafficRunning.get())
            {
                try
                {
                    // Use classic HttpURLConnection to completely disable Keep-Alive pooling
                    final java.net.HttpURLConnection conn = (java.net.HttpURLConnection) targetUrl.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Connection", "close"); // Force a fresh TCP socket
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);

                    final int status = conn.getResponseCode();

                    if (status != 200)
                    {
                        throw new IllegalStateException("Dropped traffic! Status: " + status);
                    }

                    // Consume the stream to ensure the socket closes cleanly
                    try (final java.io.InputStream is = conn.getInputStream())
                    {
                        is.readAllBytes();
                    }

                    successfulRequests.incrementAndGet();
                }
                catch (final Throwable e)
                {
                    backgroundError.set(e);
                    isTrafficRunning.set(false);
                }
            }
        });

        Thread.sleep(1000);

        // 3. Hot reload
        triggerHotReload("configs/hot-reload/routes-v2.yaml");

        // Wait a few seconds for the HotReloadService to swap the routes
        Thread.sleep(1000);

        // 4. Stop the traffic simulation
        isTrafficRunning.set(false);
        trafficThread.join();

        // 5. Assertions
        assertNull(backgroundError.get(), "Traffic thread encountered an error during reload: " + backgroundError.get());
        assertTrue(successfulRequests.get() > 50, "Not enough background traffic generated to prove stability");

        // 6. Verify the new route was successfully loaded
        given().when().get("/v2/").then().statusCode(200);
        given().when().get("/v1/").then().statusCode(200);
    }
}