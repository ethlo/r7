package com.ethlo.r7.helidon;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalIntegrityListener;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.r7f.R7Tailer;

/**
 * Journal rules, read back through the tailer: what an {@code on_response} rule decides at
 * commit is what reaches the journal.
 */
public class JournalRulesE2ETest extends AbstractR7IntegrationTest
{
    private static final Path JOURNALS = Paths.get("journals");

    @BeforeAll
    public static void setupTopology()
    {
        Assumptions.assumeTrue(System.getProperty("r7.test.mode", "in-process").equals("in-process"), "reads the gateway's journal from the host filesystem");
        startGateway("configs/journal-rules/routes.yaml");
    }

    /**
     * A rule that lowers both directions to NONE must leave no entry at all. Deciding the end
     * record on the base levels once wrote one anyway, with nothing before it: an orphaned end.
     */
    @Test
    public void aRuleLoweringBothDirectionsToNoneLeavesNoEntryAndNoOrphanedEnd() throws Exception
    {
        final int orphansBefore = tail().orphanedEnds.get();

        final String missed = "/missed-" + UUID.randomUUID();
        given().when().get(missed).then().statusCode(404);
        // Journaled at the base levels, and read back to prove the tail has caught up.
        final String traced = "/traced-" + UUID.randomUUID();
        given().when().get(traced).then().statusCode(200);

        final Tail after = awaitJournaled(traced);
        Assertions.assertFalse(after.exchanges.containsKey(missed), "the suppressed 404 was journaled");
        Assertions.assertEquals(orphansBefore, after.orphanedEnds.get(), "the suppressed 404 left an orphaned end record");
        Assertions.assertEquals(JournalLevel.METADATA, after.exchanges.get(traced).getClientResponseLevel());
    }

    /**
     * The response tee is installed at commit, once on_response has run: a body raised to FULL
     * there is captured whole, whether r7 answered or the upstream did.
     */
    @Test
    public void aResponseRaisedToFullAtCommitIsCapturedWhole() throws Exception
    {
        final String answered = "/answered-" + UUID.randomUUID();
        given().when().get(answered).then().statusCode(201);

        final String proxied = "/proxied-" + UUID.randomUUID();
        final String upstreamBody = "from the upstream " + UUID.randomUUID();
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo(proxied)).willReturn(aResponse().withStatus(202).withBody(upstreamBody)));
        given().when().get(proxied).then().statusCode(202);

        awaitJournaled(answered);
        final Tail tail = awaitJournaled(proxied);
        assertCaptured(tail.exchanges.get(answered), "answered by r7");
        assertCaptured(tail.exchanges.get(proxied), upstreamBody);
    }

    /**
     * An upgrade the upstream refuses is an ordinary response. Only a 101 tunnel has no body to
     * capture, so the client asking to upgrade must not keep a refusal's body out of the journal.
     */
    @Test
    public void aRefusedUpgradeRaisedToFullAtCommitIsCapturedWhole() throws Exception
    {
        final String refused = "/proxied-refused-" + UUID.randomUUID();
        final String upstreamBody = "upgrade refused " + UUID.randomUUID();
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo(refused)).willReturn(aResponse().withStatus(202).withBody(upstreamBody)));
        given().header("Connection", "Upgrade").header("Upgrade", "websocket").when().get(refused).then().statusCode(202);

        assertCaptured(awaitJournaled(refused).exchanges.get(refused), upstreamBody);
    }

    /**
     * A rule's request predicates see the request as the client sent it, in on_request and in
     * on_response alike, so a RewritePath or a SetRequestHeader never changes what a rule matches.
     */
    @Test
    public void rulePredicatesSeeTheClientRequest() throws Exception
    {
        final String id = UUID.randomUUID().toString();
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo("/upstream/" + id)).willReturn(aResponse().withStatus(200).withBody("ok")));
        final String path = "/rewritten/" + id;
        given().when().get(path).then().statusCode(200);

        final JournalExchange exchange = awaitJournaled(path).exchanges.get(path);
        Assertions.assertEquals(JournalLevel.HEADERS, exchange.getClientRequestLevel());
        Assertions.assertEquals(JournalLevel.HEADERS, exchange.getClientResponseLevel());
        final List<String> rules = new ArrayList<>();
        exchange.getAttributes().getAll("gateway.journal.rule").forEach(rules::add);
        Assertions.assertEquals(List.of("client-path", "client-request"), rules.stream().sorted().toList());
    }

    private static void assertCaptured(final JournalExchange exchange, final String body) throws IOException
    {
        Assertions.assertEquals(JournalLevel.FULL, exchange.getClientResponseLevel());
        Assertions.assertEquals(body, text(exchange.getResponseBodyFragments()));
        final List<String> rules = new ArrayList<>();
        exchange.getAttributes().getAll("gateway.journal.rule").forEach(rules::add);
        Assertions.assertEquals(List.of("keep-bodies"), rules);
    }

    /**
     * The body as journaled, which is as sent: WireMock gzips, so that is undone here.
     */
    static String text(final List<ByteBuffer> fragments) throws IOException
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (final ByteBuffer fragment : fragments)
        {
            final ByteBuffer copy = fragment.duplicate();
            final byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            out.write(bytes);
        }
        final byte[] body = out.toByteArray();
        if (body.length > 1 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b)
        {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body)))
            {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    static Tail awaitJournaled(final String path) throws Exception
    {
        Tail tail = tail();
        for (int attempt = 0; attempt < 50 && !tail.exchanges.containsKey(path); attempt++)
        {
            Thread.sleep(100);
            tail = tail();
        }
        Assertions.assertTrue(tail.exchanges.containsKey(path), path + " was not journaled");
        return tail;
    }

    static final class Tail implements ExchangeCompletionListener
    {
        final Map<String, JournalExchange> exchanges = new ConcurrentHashMap<>();
        final AtomicInteger orphanedEnds = new AtomicInteger();

        @Override
        public void onComplete(final JournalExchange exchange)
        {
            final String startLine = exchange.getClientRequestStartLine();
            if (startLine != null)
            {
                // "GET /path HTTP/1.1": keyed by the path.
                final String[] parts = startLine.split(" ");
                exchanges.put(parts.length > 1 ? parts[1] : startLine, exchange);
            }
        }

        @Override
        public void onOrphanedEnd(final String requestId)
        {
            orphanedEnds.incrementAndGet();
        }
    }

    /**
     * A fresh read of the whole journal directory, with a private checkpoint so every read
     * starts from the beginning.
     */
    private static Tail tail() throws Exception
    {
        final Tail tail = new Tail();
        new R7Tailer(JOURNALS, Files.createTempDirectory("r7-journal-rules-checkpoints"), tail,
                JournalIntegrityListener.NOOP, ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1))).runTick();
        return tail;
    }
}
