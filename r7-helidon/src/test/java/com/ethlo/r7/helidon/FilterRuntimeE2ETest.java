package com.ethlo.r7.helidon;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.restassured.specification.RequestSpecification;

/**
 * Reproduces three findings from the filter-runtime review, all rooted in how a request refused
 * via {@code exchange.shortCircuit(...)} is answered by the pipeline:
 * <p>
 * M3 - response filters must see the real refusal status, not the exchange's default 200, or a
 * CircuitBreaker closes on a half-open probe that was actually refused.
 * <p>
 * L1 - response filters on a refused request must run in the same onion order a proxied
 * request's would, and the refusal's own headers must not be re-applied afterwards and overwrite
 * what a filter just set.
 */
public class FilterRuntimeE2ETest extends AbstractR7IntegrationTest
{
    @BeforeAll
    public static void setupTopology()
    {
        startGateway("configs/filter-runtime/routes.yaml");
    }

    private static int managementPort()
    {
        return R7_GATEWAY == null ? 18888 : R7_GATEWAY.getMappedPort(18888);
    }

    private static RequestSpecification management()
    {
        return given().baseUri("http://localhost").port(managementPort());
    }

    /**
     * Pulls the CircuitBreaker's own summary (its {@code state=...} text) off the management
     * endpoint's pipeline visualization for the given route - the only external window onto the
     * filter's internal state.
     */
    private static String circuitBreakerSummary(final String routeId)
    {
        return management()
                .accept("application/json")
                .when()
                .get("/")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("route_configs.find { it.id == '" + routeId + "' }.filter_nodes.summary");
    }

    @Test
    public void aRefusedHalfOpenProbeReopensTheCircuitInsteadOfClosingIt() throws InterruptedException
    {
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo("/circuit-probe")).willReturn(aResponse().withStatus(500)));

        // 1. One upstream failure trips the breaker open (failure_threshold: 1).
        given().header("X-Allow-Probe", "1").when().get("/circuit-probe").then().statusCode(500);
        assertCircuitState("circuit-probe-status", "OPEN");

        // 2. Wait out the cooldown so the next request is the half-open probe.
        Thread.sleep(300);

        // 3. The probe omits the header: CircuitBreaker's onClientRequest lets it through
        // (OPEN -> HALF_OPEN), but RequireRequestHeader then refuses it before the upstream is
        // ever reached, with the configured 503. The client always sees 503, proxied or not -
        // what is under test is what the CircuitBreaker's own response filter concluded from it.
        given().when().get("/circuit-probe").then().statusCode(503);

        // A refused probe is not evidence of recovery: the circuit must still be (or be again)
        // OPEN, never CLOSED. Buggy: onClientResponse saw the exchange's default 200 instead of
        // 503, called it a success, and closed the circuit while the probe was still in flight.
        assertCircuitState("circuit-probe-status", "OPEN");
    }

    private static void assertCircuitState(final String routeId, final String expected)
    {
        final String summary = circuitBreakerSummary(routeId);
        org.assertj.core.api.Assertions.assertThat(summary)
                .as("CircuitBreaker summary")
                .contains("state=" + expected);
    }

    @Test
    public void aRefusedRequestRunsResponseFiltersInOnionOrderAndKeepsTheirHeaders()
    {
        // RequireRequestHeader refuses (X-Required missing) before either SetResponseHeader is a
        // request-phase concern; both are still response-phase filters and both run. In onion
        // order (last-declared first, first-declared last - the same order a proxied response
        // goes through), the first-declared filter has the final say: X-Onion ends up "outer".
        // The buggy forward order left the last-declared filter's value ("inner") standing, and
        // separately, the refusal's own headers being re-applied afterwards would have stomped
        // on whichever value survived.
        given().when().get("/onion-order")
                .then()
                .statusCode(400)
                .header("X-Onion", equalTo("outer"));
    }

    @Test
    public void settingACookieOverwritesOneWhoseNameHasTrailingWhitespace()
    {
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo("/cookie-whitespace-set")).willReturn(aResponse().withStatus(200)));

        // "tenant =666" (note the space before '=') is a distinct cookie name to a strict,
        // exact-match lookup, so SetRequestCookie's own "tenant" cookie would otherwise reach the
        // upstream ALONGSIDE the untouched malicious one - which a lenient upstream cookie parser
        // (Node, Go) still reads as plain "tenant".
        given().header("Cookie", "tenant =666").when().get("/cookie-whitespace-set").then().statusCode(200);

        UPSTREAM_SERVER.verify(getRequestedFor(urlPathEqualTo("/cookie-whitespace-set"))
                .withHeader("Cookie", containing("tenant=clean")));
        UPSTREAM_SERVER.verify(0, getRequestedFor(urlPathEqualTo("/cookie-whitespace-set"))
                .withHeader("Cookie", containing("666")));
    }

    @Test
    public void removingACookieStripsOneWhoseNameHasTrailingWhitespace()
    {
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo("/cookie-whitespace-remove")).willReturn(aResponse().withStatus(200)));

        given().header("Cookie", "tenant =666").when().get("/cookie-whitespace-remove").then().statusCode(200);

        UPSTREAM_SERVER.verify(0, getRequestedFor(urlPathEqualTo("/cookie-whitespace-remove"))
                .withHeader("Cookie", containing("666")));
    }

    @Test
    public void removingACookieDoesNotSendNameEqualsNull()
    {
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo("/cookie-remove-plain")).willReturn(aResponse().withStatus(200)));

        given().cookie("removed_c", "should_be_stripped").cookie("kept_c", "should_remain")
                .when().get("/cookie-remove-plain").then().statusCode(200);

        // The old bug set the cookie's value to Java null and then string-concatenated it while
        // rebuilding the Cookie header, literally sending "removed_c=null" upstream.
        UPSTREAM_SERVER.verify(0, getRequestedFor(urlPathEqualTo("/cookie-remove-plain"))
                .withHeader("Cookie", containing("removed_c=null")));
        UPSTREAM_SERVER.verify(getRequestedFor(urlPathEqualTo("/cookie-remove-plain"))
                .withHeader("Cookie", containing("kept_c=should_remain")));
    }
}
