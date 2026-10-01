package com.ethlo.r7.helidon;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalIntegrityListener;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.r7f.R7Tailer;

/**
 * A route that keeps response bodies but not request bodies, read back through the tailer.
 * <p>
 * Below FULL the request records are held until the exchange completes, while the response
 * body is journaled as it streams — so unless the writer anchors the body first, the reader
 * sees fragments for a request id it has never heard of and discards every one of them.
 */
public class ResponseBodyJournalTest extends AbstractR7IntegrationTest
{
    private static final Path JOURNALS = Paths.get("journals");

    @BeforeAll
    public static void setupTopology()
    {
        Assumptions.assumeTrue(System.getProperty("r7.test.mode", "in-process").equals("in-process"), "reads the gateway's journal from the host filesystem");
        startGateway("configs/response-body-journal/routes.yaml");
    }

    @Test
    public void aResponseBodyJournaledAtFullIsDeliveredWithARequestBelowFull() throws Exception
    {
        final String marker = "/payload-" + UUID.randomUUID();
        final String body = "response payload " + UUID.randomUUID();
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo(marker)).willReturn(aResponse().withStatus(200).withBody(body)));

        given().when().get(marker).then().statusCode(200);

        final Map<String, JournalExchange> found = new ConcurrentHashMap<>();
        final List<String> problems = new CopyOnWriteArrayList<>();
        final Path checkpoints = Files.createTempDirectory("r7-response-body-test-checkpoints");
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

            @Override
            public void onChecksumMismatch(final JournalExchange exchange, final BodyKind kind, final BodyChecksum journaled, final BodyChecksum observed)
            {
                final String startLine = exchange.getClientRequestStartLine();
                if (startLine != null && startLine.contains(marker))
                {
                    problems.add(kind + " checksum mismatch: journaled " + journaled + ", observed " + observed);
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
        Assertions.assertEquals(List.of(), problems);
        final ByteArrayOutputStream stored = new ByteArrayOutputStream();
        for (final ByteBuffer fragment : found.get(marker).getResponseBodyFragments())
        {
            final ByteBuffer copy = fragment.duplicate();
            final byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            stored.write(bytes);
        }
        // The journal holds the bytes as they went to the client, which may be compressed.
        byte[] bytes = stored.toByteArray();
        if (bytes.length > 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B)
        {
            try (final GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes)))
            {
                bytes = in.readAllBytes();
            }
        }
        Assertions.assertEquals(body, new String(bytes, StandardCharsets.UTF_8));
    }
}
