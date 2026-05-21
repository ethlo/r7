package com.ethlo.r7.undertow;

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
                .statusCode(404);
    }
}