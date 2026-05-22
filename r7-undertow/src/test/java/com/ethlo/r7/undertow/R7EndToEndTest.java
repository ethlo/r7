package com.ethlo.r7.undertow;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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