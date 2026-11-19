package com.ethlo.r7.helidon;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.ethlo.r7.helidon.JournalRulesE2ETest.awaitJournaled;
import static com.ethlo.r7.helidon.JournalRulesE2ETest.text;
import static io.restassured.RestAssured.given;

import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ethlo.r7.journal.api.JournalExchange;

/**
 * {@code truncate_body_at}, read back through the tailer.
 */
public class JournalTruncateE2ETest extends AbstractR7IntegrationTest
{
    @BeforeAll
    public static void setupTopology()
    {
        Assumptions.assumeTrue(System.getProperty("r7.test.mode", "in-process").equals("in-process"), "reads the gateway's journal from the host filesystem");
        startGateway("configs/journal-truncate/routes.yaml");
    }

    /**
     * {@code truncate_body_at} caps what the journal keeps, never the traffic: the client gets
     * the whole response, the journal its first 16 bytes, and the end record says so. A body
     * exactly at the cap is journaled whole and not flagged.
     */
    @Test
    public void bodiesOverTruncateBodyAtAreCappedInTheJournalOnly() throws Exception
    {
        final String exact = "/capped-exact-" + UUID.randomUUID();
        final String over = "/capped-over-" + UUID.randomUUID();
        final String response = "0123456789abcdef and the rest of a longer response";
        // Not gzipped: a compressed prefix would not read back as text.
        UPSTREAM_SERVER.stubFor(post(urlPathEqualTo(exact)).willReturn(aResponse().withGzipDisabled(true).withStatus(200).withBody("0123456789abcdef")));
        UPSTREAM_SERVER.stubFor(post(urlPathEqualTo(over)).willReturn(aResponse().withGzipDisabled(true).withStatus(200).withBody(response)));

        given().body("0123456789abcdef").when().post(exact).then().statusCode(200);
        final String received = given().body("0123456789abcdefX").when().post(over)
                .then().statusCode(200).extract().asString();
        Assertions.assertEquals(response, received, "the client gets the whole body");

        awaitJournaled(exact);
        final JournalRulesE2ETest.Tail tail = awaitJournaled(over);

        final JournalExchange atCap = tail.exchanges.get(exact);
        Assertions.assertEquals("0123456789abcdef", text(atCap.getRequestBodyFragments()));
        Assertions.assertEquals("0123456789abcdef", text(atCap.getResponseBodyFragments()));
        Assertions.assertFalse(atCap.isRequestBodyTruncated());
        Assertions.assertFalse(atCap.isResponseBodyTruncated());

        final JournalExchange overCap = tail.exchanges.get(over);
        Assertions.assertEquals("0123456789abcdef", text(overCap.getRequestBodyFragments()));
        Assertions.assertEquals("0123456789abcdef", text(overCap.getResponseBodyFragments()));
        Assertions.assertTrue(overCap.isRequestBodyTruncated());
        Assertions.assertTrue(overCap.isResponseBodyTruncated());
        Assertions.assertEquals(17, overCap.getRequestBodyBytes(), "the traffic counters see the whole body");
        Assertions.assertEquals(overCap.getJournaledResponseChecksum(), overCap.getObservedResponseChecksum(), "the checksum covers the prefix");
    }

}
