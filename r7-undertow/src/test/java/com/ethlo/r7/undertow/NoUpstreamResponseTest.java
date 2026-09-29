package com.ethlo.r7.undertow;

import static io.restassured.RestAssured.given;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.restassured.response.Response;

/**
 * The 503 r7 sends when a route has no available upstream is generic: the route ID is
 * configuration, and naming it tells a client how the routes are laid out.
 */
public class NoUpstreamResponseTest extends AbstractR7IntegrationTest
{
    @BeforeAll
    public static void setupTopology()
    {
        startGateway("configs/no-upstream/routes.yaml");
    }

    @Test
    public void theNoUpstreamResponseDoesNotNameTheRoute() throws InterruptedException
    {
        // The health check needs a probe or two to mark the closed port down; until then the
        // request goes to the dead target, and the proxy's own connection failure answers - a
        // 503 too, but with no body. r7's answer is the one with a body.
        Response response = null;
        for (int attempt = 0; attempt < 50; attempt++)
        {
            response = given().when().get("/dead/anything");
            if (response.statusCode() == 503 && !response.asString().isEmpty())
            {
                break;
            }
            Thread.sleep(200);
        }

        Assertions.assertEquals(503, response.statusCode(), "the upstream was never marked unavailable");
        Assertions.assertFalse(response.asString().isEmpty(), "r7 never answered for the unavailable upstream");
        Assertions.assertFalse(response.asString().contains("internal-billing-v2"), response.asString());
        Assertions.assertEquals("nosniff", response.header("X-Content-Type-Options"));
    }
}
