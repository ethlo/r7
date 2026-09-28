package com.ethlo.r7.undertow;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * BasicAuth's removal of the client's credentials is an upstream-phase step on the route the
 * request matched. When that route's upstream is down, the request continues on its fallback
 * route - and the step must still run, or the fallback upstream receives every user's password.
 */
public class BasicAuthFallbackTest extends AbstractR7IntegrationTest
{
    @BeforeAll
    public static void setupTopology()
    {
        startGateway("configs/basic-auth-fallback/routes.yaml");
    }

    @Test
    public void verifiedCredentialsNeverReachTheFallbackUpstream() throws InterruptedException
    {
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo("/protected/data")).willReturn(aResponse().withStatus(200)));
        final String credentials = "Basic " + Base64.getEncoder().encodeToString("alice:secret".getBytes(StandardCharsets.UTF_8));

        // The health check needs a probe or two to mark the closed port down; until then the
        // request goes to the dead primary and fails.
        int status = -1;
        for (int attempt = 0; attempt < 50 && status != 200; attempt++)
        {
            status = given().header("Authorization", credentials).when().get("/protected/data").then().extract().statusCode();
            if (status != 200)
            {
                Thread.sleep(200);
            }
        }
        Assertions.assertEquals(200, status, "request never reached the fallback route");

        UPSTREAM_SERVER.verify(getRequestedFor(urlPathEqualTo("/protected/data"))
                .withHeader("X-Fallback", com.github.tomakehurst.wiremock.client.WireMock.equalTo("yes"))
                .withHeader("Authorization", absent()));
    }

    @Test
    public void theFallbackIsStillGuardedByTheMatchedRoutesAuthentication()
    {
        given().when().get("/protected/data").then().statusCode(401);
    }

    private static String alice()
    {
        return "Basic " + Base64.getEncoder().encodeToString("alice:secret".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void verifiedCredentialsAreNotForwardedOnTheMatchedRoute()
    {
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo("/direct/data")).willReturn(aResponse().withStatus(200)));

        given().header("Authorization", alice()).when().get("/direct/data").then().statusCode(200);

        UPSTREAM_SERVER.verify(getRequestedFor(urlPathEqualTo("/direct/data")).withHeader("Authorization", absent()));
    }

    /**
     * InjectBasicAuth set the very same value the client sent, before BasicAuth ran: it is the
     * upstream's credential and must arrive, which no comparison of values could guarantee.
     */
    @Test
    public void anInjectedCredentialIdenticalToTheClientsStillReachesTheUpstream()
    {
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo("/direct-inject/data")).willReturn(aResponse().withStatus(200)));

        given().header("Authorization", alice()).when().get("/direct-inject/data").then().statusCode(200);

        UPSTREAM_SERVER.verify(getRequestedFor(urlPathEqualTo("/direct-inject/data"))
                .withHeader("Authorization", com.github.tomakehurst.wiremock.client.WireMock.equalTo(alice())));
    }
}
