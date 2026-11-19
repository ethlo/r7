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

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalIntegrityListener;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.r7f.R7Tailer;

/**
 * The upstream request leg is what the upstream was sent. The relay writes Host, the
 * X-Forwarded-* headers and the target's own path into the head after the pipeline is done with
 * the request, so a journal built from the pipeline's view recorded a request nobody received.
 */
public class UpstreamRequestJournalTest extends AbstractR7IntegrationTest
{
    private static final Path JOURNALS = Paths.get("journals");

    @BeforeAll
    public static void setupTopology()
    {
        Assumptions.assumeTrue(System.getProperty("r7.test.mode", "in-process").equals("in-process"), "reads the gateway's journal from the host filesystem");
        startGateway("configs/upstream-request-journal/routes.yaml");
    }

    @Test
    public void theUpstreamRequestIsJournaledAsSent() throws Exception
    {
        final String marker = "/sent/" + UUID.randomUUID();
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo("/base" + marker)).willReturn(aResponse().withStatus(200)));
        given().header("Host", "public.example").when().get(marker).then().statusCode(200);

        final JournalExchange exchange = readBack(marker);
        Assertions.assertEquals("GET /base" + marker + " HTTP/1.1", exchange.getUpstreamRequestStartLine());
        final GatewayHeaders sent = exchange.getUpstreamRequestHeaders();
        Assertions.assertEquals("localhost:" + UPSTREAM_SERVER.port(), sent.getFirst("Host"), "the target's Host, not the client's");
        Assertions.assertEquals("public.example", sent.getFirst("X-Forwarded-Host"));
        Assertions.assertNotNull(sent.getFirst("X-Forwarded-For"), "the relay sets X-Forwarded-For on every forwarded request");
        Assertions.assertEquals("http", sent.getFirst("X-Forwarded-Proto"));
        Assertions.assertEquals("public.example", exchange.getClientRequestHeaders().getFirst("Host"), "the client request is still the client's");
    }

    @Test
    public void aStatusOutsideTheCommonFewKeepsItsReasonPhrase() throws Exception
    {
        final String marker = "/status/" + UUID.randomUUID();
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo(marker)).willReturn(aResponse().withStatus(307).withHeader("Location", "/elsewhere")));
        given().redirects().follow(false).when().get(marker).then().statusCode(307);

        final JournalExchange exchange = readBack(marker);
        Assertions.assertEquals("HTTP/1.1 307 Temporary Redirect", exchange.getUpstreamResponseStartLine());
        Assertions.assertEquals("HTTP/1.1 307 Temporary Redirect", exchange.getClientResponseStartLine());
    }

    private static JournalExchange readBack(final String marker) throws Exception
    {
        final Map<String, JournalExchange> found = new ConcurrentHashMap<>();
        final Path checkpoints = Files.createTempDirectory("r7-upstream-request-test-checkpoints");
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
