package com.ethlo.r7.helidon;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;

import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalLevel;

/**
 * A request body at {@code FULL} is journaled whether or not the client sent an Upgrade header.
 */
public class JournalRequestUpgradeE2ETest extends AbstractR7IntegrationTest
{
    @BeforeAll
    public static void setupTopology()
    {
        Assumptions.assumeTrue(System.getProperty("r7.test.mode", "in-process").equals("in-process"), "reads the gateway's journal from the host filesystem");
        startGateway("configs/journal-request-upgrade/routes.yaml");
    }

    /**
     * Helidon routes an upgrade request with a body as an ordinary exchange, and the relay refuses
     * to switch protocols for it: nothing makes it different from any other request, so its body is
     * captured as the level says.
     */
    @Test
    public void aRequestBodyIsCapturedWhenTheClientAsksToUpgrade() throws Exception
    {
        final String path = "/proxied-" + UUID.randomUUID();
        final String requestBody = "request body " + UUID.randomUUID();
        UPSTREAM_SERVER.stubFor(post(urlPathEqualTo(path)).willReturn(aResponse().withStatus(202)));

        // 502 is the relay refusing a 101 for a request with a body; 202 would be the upstream's own answer
        given().header("Connection", "Upgrade").header("Upgrade", "websocket").body(requestBody).when().post(path).then()
                .statusCode(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(202), org.hamcrest.Matchers.is(502)));

        final JournalExchange exchange = JournalRulesE2ETest.awaitJournaled(path).exchanges.get(path);
        Assertions.assertEquals(JournalLevel.FULL, exchange.getClientRequestLevel());
        Assertions.assertEquals(requestBody, JournalRulesE2ETest.text(exchange.getRequestBodyFragments()));
    }
}
