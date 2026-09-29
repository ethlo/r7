package com.ethlo.r7.undertow;

import static io.restassured.RestAssured.given;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
 * Overrides that lower both directions to NONE must leave no entry at all. Deciding the end
 * record on the base levels once wrote one anyway, with nothing before it: an orphaned end.
 */
public class UnroutedJournalSuppressionTest extends AbstractR7IntegrationTest
{
    private static final Path JOURNALS = Paths.get("journals");

    @BeforeAll
    public static void setupTopology()
    {
        Assumptions.assumeTrue(System.getProperty("r7.test.mode", "in-process").equals("in-process"), "reads the gateway's journal from the host filesystem");
        startGateway("configs/unrouted-suppressed/routes.yaml");
    }

    @Test
    public void aSuppressedStatusLeavesNoEntryAndNoOrphanedEnd() throws Exception
    {
        final int orphansBefore = tail().orphanedEnds.get();

        final String missed = "/missed-" + UUID.randomUUID();
        given().when().get(missed).then().statusCode(404);
        // Journaled at the base levels, and read back to prove the tail has caught up.
        final String traced = "/traced-" + UUID.randomUUID();
        given().when().request("TRACE", traced).then().statusCode(501);

        Tail after = tail();
        for (int attempt = 0; attempt < 50 && !after.startLines.stream().anyMatch(l -> l.contains(traced)); attempt++)
        {
            Thread.sleep(100);
            after = tail();
        }

        Assertions.assertTrue(after.startLines.stream().anyMatch(l -> l.contains(traced)), "the unsuppressed refusal was not journaled");
        Assertions.assertFalse(after.startLines.stream().anyMatch(l -> l.contains(missed)), "the suppressed 404 was journaled");
        Assertions.assertEquals(orphansBefore, after.orphanedEnds.get(), "the suppressed 404 left an orphaned end record");
    }

    private static final class Tail implements ExchangeCompletionListener
    {
        final Set<String> startLines = ConcurrentHashMap.newKeySet();
        final AtomicInteger orphanedEnds = new AtomicInteger();

        @Override
        public void onComplete(final JournalExchange exchange)
        {
            if (exchange.getClientRequestStartLine() != null)
            {
                startLines.add(exchange.getClientRequestStartLine());
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
        new R7Tailer(JOURNALS, Files.createTempDirectory("r7-unrouted-suppression-checkpoints"), tail,
                JournalIntegrityListener.NOOP, ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1))).runTick();
        return tail;
    }
}
