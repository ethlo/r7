package com.ethlo.r7.undertow;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * StaticContent over /tmp, with hidden files refused by default (/static/) and served on a
 * route that opts in (/static-hidden/).
 */
public class StaticContentHiddenFilesE2ETest extends AbstractR7IntegrationTest
{
    @BeforeAll
    public static void setupTopology() throws Exception
    {
        startGateway("configs/static-hidden/routes.yaml");
        writeContainerOrHostFile("/tmp/.r7-hidden-test", "SECRET=1");
        writeContainerOrHostFile("/tmp/.well-known/r7-test.txt", "well-known content");
    }

    @Test
    public void aDotfileIsNotServed()
    {
        given().when().get("/static/.r7-hidden-test").then().statusCode(404).header("X-Content-Type-Options", equalTo("nosniff"));
    }

    @Test
    public void wellKnownIsStillServed()
    {
        given().when().get("/static/.well-known/r7-test.txt").then().statusCode(200).body(equalTo("well-known content"));
    }

    @Test
    public void aDotfileIsServedWhenTheRouteOptsIn()
    {
        given().when().get("/static-hidden/.r7-hidden-test").then().statusCode(200).body(equalTo("SECRET=1"));
    }
}
