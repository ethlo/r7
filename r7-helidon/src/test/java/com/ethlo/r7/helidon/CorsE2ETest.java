package com.ethlo.r7.helidon;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Through the real pipeline: a short-circuited preflight gets its Vary from the response phase,
 * which must survive the short-circuit headers being applied.
 */
public class CorsE2ETest extends AbstractR7IntegrationTest
{
    @BeforeAll
    public static void setupTopology()
    {
        startGateway("configs/cors/routes.yaml");
    }

    @Test
    public void aPreflightVariesByOrigin()
    {
        given().header("Origin", "https://app.example").header("Access-Control-Request-Method", "GET")
                .when().options("/cors/x")
                .then().statusCode(204)
                .header("Access-Control-Allow-Origin", equalTo("https://app.example"))
                .header("Vary", containsString("Origin"));
    }

    @Test
    public void anUpstreamResponseVariesByOriginAndKeepsItsOwnVary()
    {
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo("/cors/y")).willReturn(aResponse().withStatus(200).withHeader("Vary", "Accept-Encoding")));

        final java.util.List<String> vary = given().when().get("/cors/y")
                .then().statusCode(200)
                .extract().headers().getValues("Vary");

        org.assertj.core.api.Assertions.assertThat(vary).containsExactly("Accept-Encoding", "Origin");
    }
}
