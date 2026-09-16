package com.ethlo.r7.r7f;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalIntegrityListener;

/**
 * Reads a journal directory and reports everything the reader was able to determine —
 * not only what was reconstructed, but what was incomplete, damaged or missing.
 * <p>
 * Tests assert on {@link Stats}: a run is only correct if the completed count matches
 * <em>and</em> every failure counter is zero.
 */
public class JournalAnalyzer implements ExchangeCompletionListener, JournalIntegrityListener
{
    private static final Logger logger = LoggerFactory.getLogger(JournalAnalyzer.class);

    private final Path journalDir;
    private final Stats stats = new Stats();

    public JournalAnalyzer(Path journalDir)
    {
        this.journalDir = journalDir;
    }

    public Stats analyze() throws IOException
    {
        final long started = System.nanoTime();
        final R7Tailer tailer = new R7Tailer(journalDir, Duration.ofMinutes(1), this, this);
        tailer.runTick();

        logger.info("Analyzed {} full exchanges in {}ms",
                stats.completedExchanges, (System.nanoTime() - started) / 1_000_000L
        );
        return stats;
    }

    /* ---------- exchange level ---------- */

    @Override
    public void onComplete(JournalExchange exchange)
    {
        // One call = one fully reconstructed Request/Response pair
        stats.completedExchanges++;
    }

    @Override
    public void onIncompleteEnd(JournalExchange exchange, IncompleteReason reason)
    {
        stats.incompleteEnds++;
        stats.problems.add("incomplete end " + exchange.getRequestId() + ": " + reason);
    }

    @Override
    public void onAbandoned(JournalExchange exchange, IncompleteReason reason)
    {
        stats.abandonedExchanges++;
        stats.problems.add("abandoned " + exchange.getRequestId() + ": " + reason);
    }

    @Override
    public void onOrphanedEnd(String requestId)
    {
        stats.orphanedEnds++;
        stats.problems.add("orphaned end: " + requestId);
    }

    @Override
    public void onOrphanedBody(String requestId, BodyKind kind)
    {
        stats.orphanedBodies++;
        stats.problems.add("orphaned " + kind + " body: " + requestId);
    }

    @Override
    public void onChecksumMismatch(JournalExchange exchange, BodyKind kind, long journaled, long observed)
    {
        stats.checksumMismatches++;
        stats.problems.add("checksum mismatch " + exchange.getRequestId() + " " + kind
                + ": journaled=" + journaled + " observed=" + observed);
    }

    /* ---------- journal level ---------- */

    @Override
    public void onEntriesMissing(String segment, long offset, int expectedSequence, int foundSequence, int missingCount)
    {
        stats.missingEntries += missingCount;
        stats.problems.add("missing " + missingCount + " entries in " + segment + " at " + offset
                + " (expected #" + expectedSequence + ", found #" + foundSequence + ")");
    }

    @Override
    public void onCorruptRegion(String segment, long offset, long bytesSkipped, String reason)
    {
        stats.corruptRegions++;
        stats.bytesSkipped += bytesSkipped;
        stats.problems.add("corrupt region in " + segment + " at " + offset + " (" + bytesSkipped + " bytes): " + reason);
    }

    @Override
    public void onSequenceRegression(String segment, long offset, int expectedSequence, int foundSequence)
    {
        stats.sequenceRegressions++;
        stats.problems.add("sequence regression in " + segment + " at " + offset
                + " (expected #" + expectedSequence + ", found #" + foundSequence + ")");
    }

    @Override
    public void onSegmentQuarantined(String segment, String reason)
    {
        stats.quarantinedSegments++;
        stats.problems.add("quarantined " + segment + ": " + reason);
    }

    @Override
    public void onSegmentRecovered(String segment, long dataEnd, long discardedBytes, long recordsRecovered)
    {
        stats.recoveredSegments++;
        stats.recoveredRecords += recordsRecovered;
    }

    public static class Stats
    {
        public long completedExchanges = 0;

        // Exchange-level failures
        /**
         * Ended, but not usable as a complete record.
         */
        public long incompleteEnds = 0;

        /**
         * Dropped without ever seeing an end event.
         */
        public long abandonedExchanges = 0;
        public long orphanedEnds = 0;
        public long orphanedBodies = 0;
        public long checksumMismatches = 0;

        // Journal-level damage
        public long missingEntries = 0;
        public long corruptRegions = 0;
        public long bytesSkipped = 0;
        public long sequenceRegressions = 0;
        public long quarantinedSegments = 0;

        // Recovery activity (not in itself a failure)
        public long recoveredSegments = 0;
        public long recoveredRecords = 0;

        /**
         * Human-readable description of every problem observed, in the order found.
         * A failing assertion on a counter can print this instead of leaving the reader
         * to go digging through logs.
         */
        public final List<String> problems = new ArrayList<>();

        /**
         * True when nothing was lost, damaged or left incomplete.
         */
        public boolean isClean()
        {
            return incompleteEnds == 0
                    && abandonedExchanges == 0
                    && orphanedEnds == 0
                    && orphanedBodies == 0
                    && checksumMismatches == 0
                    && missingEntries == 0
                    && corruptRegions == 0
                    && sequenceRegressions == 0
                    && quarantinedSegments == 0;
        }

        @Override
        public String toString()
        {
            return "Stats{completed=" + completedExchanges
                    + ", incompleteEnds=" + incompleteEnds
                    + ", abandoned=" + abandonedExchanges
                    + ", orphanedEnds=" + orphanedEnds
                    + ", orphanedBodies=" + orphanedBodies
                    + ", checksumMismatches=" + checksumMismatches
                    + ", missingEntries=" + missingEntries
                    + ", corruptRegions=" + corruptRegions
                    + ", bytesSkipped=" + bytesSkipped
                    + ", sequenceRegressions=" + sequenceRegressions
                    + ", quarantined=" + quarantinedSegments
                    + ", recovered=" + recoveredSegments
                    + ", recoveredRecords=" + recoveredRecords
                    + (problems.isEmpty() ? "" : ", problems=" + problems)
                    + '}';
        }
    }
}
