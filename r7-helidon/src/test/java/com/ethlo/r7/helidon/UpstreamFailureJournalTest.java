package com.ethlo.r7.helidon;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalIntegrityListener;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.r7f.R7Tailer;

/**
 * The upstream response leg holds only what an upstream sent. When none answered - a refused
 * connect, a read timeout - the client gets r7's own 503 or 504, and that is journaled as the
 * client response alone: recorded as the upstream's, it would put words in the upstream's mouth.
 */
public class UpstreamFailureJournalTest extends AbstractR7IntegrationTest
{
    private static final Path JOURNALS = Paths.get("journals");

    @BeforeAll
    public static void setupTopology()
    {
        Assumptions.assumeTrue(System.getProperty("r7.test.mode", "in-process").equals("in-process"), "reads the gateway's journal from the host filesystem");
        startGateway("configs/upstream-failure-journal/routes.yaml");
    }

    @Test
    public void aRefusedConnectJournalsTheForwardedRequestButNoUpstreamResponse() throws Exception
    {
        final String marker = "/refused/" + UUID.randomUUID();
        given().when().get(marker).then().statusCode(503);

        final JournalExchange exchange = readBack(marker);
        Assertions.assertTrue(exchange.wasProxied());
        Assertions.assertNotNull(exchange.getUpstreamRequestStartLine(), "the request was handed to the upstream");
        Assertions.assertNull(exchange.getUpstreamResponseStartLine(), "no upstream answered, yet an upstream response was journaled");
        Assertions.assertEquals(503, exchange.getStatus());
        Assertions.assertTrue(exchange.getClientResponseStartLine().contains("503"), exchange.getClientResponseStartLine());
    }

    @Test
    public void aReadTimeoutJournalsTheForwardedRequestButNoUpstreamResponse() throws Exception
    {
        final String marker = "/slow/" + UUID.randomUUID();
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo(marker)).willReturn(aResponse().withStatus(200).withFixedDelay(2_000)));
        given().when().get(marker).then().statusCode(504);

        final JournalExchange exchange = readBack(marker);
        Assertions.assertTrue(exchange.wasProxied());
        Assertions.assertNotNull(exchange.getUpstreamRequestStartLine(), "the request was sent to the upstream");
        Assertions.assertNull(exchange.getUpstreamResponseStartLine(), "no upstream answered, yet an upstream response was journaled");
        Assertions.assertEquals(-1, exchange.getProxyFirstByteReceivedTs(), "no response head was read");
        Assertions.assertEquals(504, exchange.getStatus());
    }

    @Test
    public void anUpstreamErrorIsStillJournaledAsTheUpstreamResponse() throws Exception
    {
        final String marker = "/answered/" + UUID.randomUUID();
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo(marker)).willReturn(aResponse().withStatus(502).withHeader("X-Upstream", "yes")));
        given().when().get(marker).then().statusCode(502);

        final JournalExchange exchange = readBack(marker);
        Assertions.assertNotNull(exchange.getUpstreamResponseStartLine(), "the upstream's own 502 must be journaled as its response");
        Assertions.assertTrue(exchange.getUpstreamResponseStartLine().contains("502"), exchange.getUpstreamResponseStartLine());
        // Redacted to a fingerprint, as it is not on the safe list; what matters is that it is there.
        Assertions.assertNotNull(exchange.getUpstreamResponseHeaders().getFirst("X-Upstream"));
    }

    private static JournalExchange readBack(final String marker) throws Exception
    {
        final Map<String, JournalExchange> found = new ConcurrentHashMap<>();
        final Path checkpoints = Files.createTempDirectory("r7-upstream-failure-test-checkpoints");
        final R7Tailer tailer = new R7Tailer(JOURNALS, checkpoints, new ExchangeCompletionListener()
        {
            @Override
            public void onComplete(final JournalExchange exchange)
            {
                final String startLine = exchange.getClientRequestStartLine();
                if (startLine != null && startLine.contains(marker))
                {
                    found.put(marker, exchange);
                }
            }
        }, JournalIntegrityListener.NOOP, ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1)));

        for (int attempt = 0; attempt < 50 && !found.containsKey(marker); attempt++)
        {
            tailer.runTick();
            if (!found.containsKey(marker))
            {
                Thread.sleep(100);
            }
        }
        Assertions.assertTrue(found.containsKey(marker), "no journal entry for " + marker);
        return found.get(marker);
    }
}
