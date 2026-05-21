package com.ethlo.r7.undertow;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
}