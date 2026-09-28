package com.ethlo.r7.undertow;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.restassured.specification.RequestSpecification;

/**
 * The management endpoint renders route configuration; secrets in it must not be rendered.
 */
public class ManagementEndpointTest extends AbstractR7IntegrationTest
{
    @BeforeAll
    public static void setupTopology()
    {
        startGateway("configs/management/management-routes.yaml");
    }

    private static RequestSpecification management()
    {
        return given()
                .baseUri("http://localhost")
                .port(R7_GATEWAY == null ? 18888 : R7_GATEWAY.getMappedPort(18888));
    }

    @Test
    public void statusJsonMasksCredentialsAndInjectedSecrets()
    {
        management()
                .accept("application/json")
                .when()
                .get("/")
                .then()
                .statusCode(200)
                .body(not(containsString("upstream-s3cret-value")))
                .body(not(containsString("header-s3cret-token")))
                .body(not(containsString("cookie-s3cret-value")))
                .body(not(containsString("query-s3cret-value")))
                .body(not(containsString("response-cookie-s3cret")))
                .body(not(containsString("url-s3cret-pass")))
                .body(containsString("upstream-svc"))
                .body(containsString("application/visible+json"))
                .body(containsString("******"));
    }

    @Test
    public void onlyReadsAreAccepted()
    {
        management()
                .when()
                .post("/")
                .then()
                .statusCode(405)
                .header("Allow", equalTo("GET, HEAD"))
                .header("X-Content-Type-Options", equalTo("nosniff"))
                .header("X-Frame-Options", equalTo("DENY"))
                .header("Cache-Control", equalTo("no-store"))
                .header("Referrer-Policy", equalTo("no-referrer"));
    }

    @Test
    public void responsesCarrySecurityHeaders()
    {
        management()
                .when()
                .get("/")
                .then()
                .statusCode(200)
                .header("X-Content-Type-Options", equalTo("nosniff"))
                .header("X-Frame-Options", equalTo("DENY"))
                .header("Cache-Control", equalTo("no-store"))
                .header("Referrer-Policy", equalTo("no-referrer"));
    }
}
