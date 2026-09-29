package com.ethlo.r7.undertow;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;

import java.time.Duration;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class RegexBudgetE2ETest extends AbstractR7IntegrationTest
{
    @BeforeAll
    public static void setupTopology()
    {
        startGateway("configs/regex-budget/routes.yaml");
    }

    /**
     * A crafted path against a catastrophic route pattern fails closed quickly, and the gateway
     * keeps serving: no I/O thread is left spinning.
     */
    @Test
    @Timeout(20)
    public void aCatastrophicMatchFailsClosedAndTheGatewayKeepsServing()
    {
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo("/ok")).willReturn(aResponse().withStatus(200)));
        final String evil = "/" + "a".repeat(40) + "!";

        // Warm-up request, so class loading and JIT compilation do not count against the bound
        given().when().get(evil).then().statusCode(500);

        for (int i = 0; i < 16; i++)
        {
            final long start = System.nanoTime();
            given().when().get(evil).then().statusCode(500);
            final Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            Assertions.assertTrue(elapsed.compareTo(Duration.ofMillis(100)) < 0, "request took " + elapsed.toMillis() + " ms");
        }

        given().when().get("/ok").then().statusCode(200);
    }

    /**
     * Exhausting the budget in a filter is a deliberate refusal: a 500 that still goes through the
     * short-circuit path, so response filters run (and the journal entry is completed).
     */
    @Test
    @Timeout(20)
    public void aBudgetExhaustedInARequestFilterStillRunsResponseFilters()
    {
        given().header("X-Token", "a".repeat(40) + "!").when().get("/header-check")
                .then().statusCode(500)
                .header("X-Response-Filter", org.hamcrest.Matchers.equalTo("ran"));
    }

    @Test
    @Timeout(20)
    public void aBudgetExhaustedInAnUpstreamFilterStillRunsResponseFilters()
    {
        given().when().get("/rewrite/" + "a".repeat(40) + "!")
                .then().statusCode(500)
                .header("X-Response-Filter", org.hamcrest.Matchers.equalTo("ran"));
    }
}
