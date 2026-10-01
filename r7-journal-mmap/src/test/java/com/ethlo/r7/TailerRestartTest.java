package com.ethlo.r7;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalIntegrityListener;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.r7f.R7Tailer;
import com.ethlo.r7.r7f.R7fConstants;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.r7f.R7fJournalProvider;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * A tailer that stops, gracefully or not, must not lose the exchanges it was assembling.
 * <p>
 * Reading an entry and delivering the exchange it belongs to are different things: an
 * exchange is assembled in memory until its end event, and the checkpoint says only how far
 * reading got. Every exchange open when the process stopped had its earlier entries
 * checkpointed as read and its state gone, so a SIGKILL lost it and turned its end event
 * into an orphan. The tailer now records where each open exchange began, and rebuilds them on
 * restart by replaying those entries for those exchanges only — so nothing completed before
 * the stop is delivered again.
 */
class TailerRestartTest
{
    private static final int SEGMENT_SIZE = 256 * 1024;
    private static final ReassemblyOptions OPTIONS = ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1));

    @TempDir
    Path journalDir;

    @Test
    void anExchangeOpenAtAHardKillIsCompletedAfterTheRestart() throws IOException
    {
        // Segment 1: the first half of an open exchange, with a whole one in between. The
        // replay starts at the open exchange's first entry, so "done" lies inside it.
        try (R7fJournal journal = journal())
        {
            writeClientRequest(journal, "open");
            writeComplete(journal, "done");
            writeRequestBody(journal, "open", "first-");
        }

        final CollectingSink beforeKill = new CollectingSink();
        new R7Tailer(journalDir, beforeKill, beforeKill, OPTIONS).runTick();
        assertThat(beforeKill.deliveries).containsExactly("done");
        // No shutdown(): this is the SIGKILL.

        // Segment 2: the rest of the open exchange.
        try (R7fJournal journal = journal())
        {
            writeRequestBody(journal, "open", "second");
            writeEnd(journal, "open");
        }

        final CollectingSink afterRestart = new CollectingSink();
        new R7Tailer(journalDir, afterRestart, afterRestart, OPTIONS).runTick();

        assertThat(afterRestart.deliveries)
                .as("the open exchange completes, and the finished one is not delivered again. sink: %s", afterRestart)
                .containsExactly("open");
        assertThat(body(afterRestart.completed.get("open")))
                .as("with the body read before the kill as well as after it")
                .isEqualTo("first-second");
        assertThat(afterRestart.orphanedEnds).as("sink: %s", afterRestart).isEmpty();
        assertThat(afterRestart.orphanedBodies).as("sink: %s", afterRestart).isEmpty();
        assertThat(afterRestart.abandoned).as("sink: %s", afterRestart).isEmpty();
    }

    /**
     * A graceful stop used to report open exchanges as abandoned, the best it could do. Now it
     * leaves them to be completed after the restart, like a hard kill does.
     */
    @Test
    void aGracefulStopNoLongerAbandonsOpenExchanges() throws IOException
    {
        try (R7fJournal journal = journal())
        {
            writeClientRequest(journal, "open");
        }

        final CollectingSink beforeStop = new CollectingSink();
        final R7Tailer tailer = new R7Tailer(journalDir, beforeStop, beforeStop, OPTIONS);
        tailer.runTick();
        tailer.shutdown();
        assertThat(beforeStop.abandoned).as("sink: %s", beforeStop).isEmpty();

        try (R7fJournal journal = journal())
        {
            writeEnd(journal, "open");
        }

        final CollectingSink afterRestart = new CollectingSink();
        new R7Tailer(journalDir, afterRestart, afterRestart, OPTIONS).runTick();
        assertThat(afterRestart.deliveries).as("sink: %s", afterRestart).containsExactly("open");
    }

    /**
     * What is owed to a restart stays owed until it is paid: a tailer that stops again before
     * its first tick must write the resume point back, not an empty one.
     */
    @Test
    void aSecondRestartBeforeTheReplayKeepsTheExchange() throws IOException
    {
        try (R7fJournal journal = journal())
        {
            writeClientRequest(journal, "open");
        }
        final CollectingSink first = new CollectingSink();
        new R7Tailer(journalDir, first, first, OPTIONS).runTick();

        final CollectingSink second = new CollectingSink();
        new R7Tailer(journalDir, second, second, OPTIONS).shutdown();

        // And again after a replay that found nothing to complete yet.
        final CollectingSink third = new CollectingSink();
        new R7Tailer(journalDir, third, third, OPTIONS).runTick();

        try (R7fJournal journal = journal())
        {
            writeEnd(journal, "open");
        }
        final CollectingSink fourth = new CollectingSink();
        new R7Tailer(journalDir, fourth, fourth, OPTIONS).runTick();

        assertThat(fourth.deliveries).as("sink: %s", fourth).containsExactly("open");
        assertThat(fourth.orphanedEnds).isEmpty();
    }

    /**
     * An exchange held for a consumer that refused it is complete but only in memory, and its
     * end entry is where the segment stalled. After a restart that end entry is read again,
     * and it must find the exchange rebuilt, not report an orphan.
     */
    @Test
    void anExchangeRefusedByTheConsumerSurvivesARestart() throws IOException
    {
        try (R7fJournal journal = journal())
        {
            writeComplete(journal, "before");
            writeClientRequest(journal, "refused");
            writeRequestBody(journal, "refused", "payload");
            writeEnd(journal, "refused");
            writeComplete(journal, "after");
        }

        final CollectingSink collected = new CollectingSink();
        final ExchangeCompletionListener refusing = new ExchangeCompletionListener()
        {
            @Override
            public void onComplete(final JournalExchange exchange)
            {
                if (exchange.getRequestId().equals("refused"))
                {
                    throw new UncheckedIOException(new IOException("sink unavailable"));
                }
                collected.onComplete(exchange);
            }
        };
        new R7Tailer(journalDir, refusing, JournalIntegrityListener.NOOP, OPTIONS).runTick();
        assertThat(collected.deliveries).containsExactly("before");

        final CollectingSink afterRestart = new CollectingSink();
        new R7Tailer(journalDir, afterRestart, afterRestart, OPTIONS).runTick();

        assertThat(afterRestart.deliveries).as("sink: %s", afterRestart).containsExactly("refused", "after");
        assertThat(body(afterRestart.completed.get("refused"))).isEqualTo("payload");
        assertThat(afterRestart.orphanedEnds).isEmpty();
    }

    /**
     * The replay needs the segment the exchange began in. If retention deleted it in the
     * meantime the exchange cannot be rebuilt, and that is a loss to report, not a crash.
     */
    @Test
    void anExchangeWhoseFirstSegmentIsGoneIsReportedNotRebuilt() throws IOException
    {
        try (R7fJournal journal = journal())
        {
            writeClientRequest(journal, "open");
        }
        final CollectingSink first = new CollectingSink();
        new R7Tailer(journalDir, first, first, OPTIONS).runTick();

        for (final Path sealed : sealedSegments())
        {
            Files.delete(sealed);
        }
        try (R7fJournal journal = journal())
        {
            writeEnd(journal, "open");
        }

        final CollectingSink afterRestart = new CollectingSink();
        new R7Tailer(journalDir, afterRestart, afterRestart, OPTIONS).runTick();

        assertThat(afterRestart.deliveries).isEmpty();
        assertThat(afterRestart.orphanedEnds).as("sink: %s", afterRestart).containsExactly("open");
    }

    private R7fJournal journal() throws IOException
    {
        return new R7fJournal(new R7fJournalProvider(journalDir, 0, SEGMENT_SIZE, true));
    }

    private List<Path> sealedSegments() throws IOException
    {
        try (Stream<Path> s = Files.list(journalDir))
        {
            return s.filter(p -> p.getFileName().toString().endsWith(R7fConstants.R7F_FILE_EXTENSION)).toList();
        }
    }

    private static String body(final JournalExchange exchange)
    {
        return new String(CollectingSink.concat(exchange.getRequestBodyFragments()), StandardCharsets.ISO_8859_1);
    }

    private static void writeComplete(final R7fJournal journal, final String reqId) throws IOException
    {
        writeClientRequest(journal, reqId);
        writeEnd(journal, reqId);
    }

    private static void writeClientRequest(final R7fJournal journal, final String reqId) throws IOException
    {
        journal.clientRequest(JournalLevel.FULL, reqId,
                ByteBuffer.wrap(("GET /" + reqId + " HTTP/1.1").getBytes(StandardCharsets.ISO_8859_1)),
                new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
    }

    private static void writeRequestBody(final R7fJournal journal, final String reqId, final String chunk)
    {
        journal.requestBody(reqId, ByteBuffer.wrap(chunk.getBytes(StandardCharsets.ISO_8859_1)));
    }

    private static void writeEnd(final R7fJournal journal, final String reqId)
    {
        journal.endExchange(reqId, new FastGatewayAttributes(),
                1L, 2L, 200, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);
    }
}
