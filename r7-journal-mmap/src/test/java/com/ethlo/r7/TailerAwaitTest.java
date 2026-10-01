package com.ethlo.r7;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.r7f.CommitSignal;
import com.ethlo.r7.r7f.R7Tailer;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.r7f.R7fJournalProvider;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * A tailer waiting between ticks wakes when the gateway commits, not when its poll interval
 * runs out (design/live-tailing.md, steps 2 and 3).
 */
class TailerAwaitTest
{
    private static final Duration LONG_POLL = Duration.ofSeconds(30);

    @TempDir
    Path journalDir;

    @Test
    void aCommitWakesAWaitingTailerLongBeforeItsPollInterval() throws Exception
    {
        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(journalDir, 0, 256 * 1024, false)))
        {
            final CollectingSink sink = new CollectingSink();
            final R7Tailer tailer = new R7Tailer(journalDir, sink, sink, ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1)));
            tailer.runTick();
            // The first wait after the signals are found returns at once: they are new to it.
            tailer.awaitNewData(LONG_POLL);
            tailer.runTick();

            for (int i = 0; i < 20; i++)
            {
                final String reqId = "req-" + i;
                final CountDownLatch waiting = new CountDownLatch(1);
                final Thread writer = Thread.ofPlatform().start(() ->
                {
                    try
                    {
                        waiting.await();
                        Thread.sleep(2);
                        write(journal, reqId);
                    }
                    catch (final Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                });

                waiting.countDown();
                final long start = System.nanoTime();
                final boolean woken = tailer.awaitNewData(LONG_POLL);
                final long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                writer.join();
                // The client request alone is a commit, so the wake may come before the end
                // event; the exchange is complete once the writer has finished.
                tailer.runTick();

                assertThat(woken).isTrue();
                assertThat(waitedMillis).as("woken by the commit, not the %s poll", LONG_POLL).isLessThan(5_000);
                assertThat(sink.deliveries).endsWith(reqId);
            }
        }
    }

    @Test
    void withoutASignalTheTailerPollsAsBefore() throws Exception
    {
        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(journalDir, 0, 256 * 1024, false)))
        {
            Files.delete(CommitSignal.pathFor(journalDir, 0));
            final CollectingSink sink = new CollectingSink();
            final R7Tailer tailer = new R7Tailer(journalDir, sink, sink, ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1)));
            tailer.runTick();

            final long start = System.nanoTime();
            assertThat(tailer.awaitNewData(Duration.ofMillis(50))).isFalse();
            assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(Duration.ofMillis(50).toNanos());

            write(journal, "polled");
            tailer.runTick();
            assertThat(sink.deliveries).containsExactly("polled");
        }
    }

    private static void write(final R7fJournal journal, final String reqId) throws IOException
    {
        journal.clientRequest(JournalLevel.FULL, reqId,
                ByteBuffer.wrap(("GET /" + reqId + " HTTP/1.1").getBytes(StandardCharsets.ISO_8859_1)),
                new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        journal.endExchange(reqId, new FastGatewayAttributes(),
                1L, 2L, 200, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);
    }
}
