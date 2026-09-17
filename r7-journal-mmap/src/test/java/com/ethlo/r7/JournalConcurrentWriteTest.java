package com.ethlo.r7;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.r7f.JournalAnalyzer;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.r7f.R7fJournalProvider;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * Entry encoding happens on the calling thread and only the claim-copy-publish step holds the
 * journal's monitor, so several threads are inside {@link R7fJournal} at once by design.
 * <p>
 * The failure this guards against is not an exception — it is a journal that looks fine until
 * something reads it: two entries handed the same sequence, a magic published over bytes
 * another thread was still copying, or a rotation that lost the entries racing with it. Those
 * are invisible to the writer, so the assertion is made by reading the whole journal back.
 */
class JournalConcurrentWriteTest
{
    private static final int THREADS = 8;
    private static final int EXCHANGES_PER_THREAD = 1_500;

    /**
     * Small enough to rotate many times over the run. Rotation is the part of the write path
     * that mutates the most journal state, so a concurrency test that never rotates would
     * leave the riskiest branch uncovered.
     */
    private static final long SEGMENT_BYTES = 1024L * 1024L;

    @Test
    void concurrentWritersLoseNoEntriesAndCorruptNone() throws Exception
    {
        final Path dir = Files.createTempDirectory("r7f-concurrent");
        try
        {
            final R7fJournalProvider provider = new R7fJournalProvider(dir, 0, SEGMENT_BYTES, false);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(THREADS);

            try (final R7fJournal journal = new R7fJournal(provider))
            {
                for (int t = 0; t < THREADS; t++)
                {
                    final int threadIndex = t;
                    final Thread writer = new Thread(() ->
                    {
                        try
                        {
                            start.await();
                            writeExchanges(journal, threadIndex);
                        }
                        catch (final Throwable e)
                        {
                            failure.compareAndSet(null, e);
                        }
                        finally
                        {
                            done.countDown();
                        }
                    }, "journal-writer-" + t);
                    writer.start();
                }

                start.countDown();
                assertThat(done.await(2, TimeUnit.MINUTES)).as("writers finished").isTrue();
            }

            assertThat(failure.get()).as("no writer threw").isNull();

            final JournalAnalyzer.Stats stats = new JournalAnalyzer(dir).analyze();
            assertThat(stats.problems).isEmpty();
            assertThat(stats.isClean()).as("journal reads back clean").isTrue();
            assertThat(stats.completedExchanges).isEqualTo((long) THREADS * EXCHANGES_PER_THREAD);
        }
        finally
        {
            deleteRecursively(dir);
        }
    }

    private static void writeExchanges(final R7fJournal journal, final int threadIndex) throws IOException
    {
        // Per thread, because the journal consumes the buffers it is handed.
        final ByteBuffer startLine = ascii("GET /concurrent/" + threadIndex + " HTTP/1.1");
        final ByteBuffer responseLine = ascii("HTTP/1.1 200 OK");
        final ByteBuffer requestBody = ascii("{\"thread\":" + threadIndex + "}");
        final ByteBuffer responseBody = ascii("{\"status\":\"ok\"}");

        final InetAddress client = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.set("user-agent", "r7-concurrency-test");
        headers.set("content-type", "application/json");
        headers.set("x-thread", Integer.toString(threadIndex));

        for (int i = 0; i < EXCHANGES_PER_THREAD; i++)
        {
            final String id = "t" + threadIndex + "-" + i;

            journal.clientRequest(JournalLevel.FULL, id, startLine.rewind(), headers, client, IpSource.SOCKET);
            journal.upstreamRequest(JournalLevel.FULL, id, startLine.rewind(), headers);
            journal.requestBody(id, requestBody.rewind());
            journal.upstreamResponse(JournalLevel.FULL, id, 200, responseLine.rewind(), headers);
            journal.responseBody(id, responseBody.rewind());
            journal.clientResponse(JournalLevel.FULL, id, 200, responseLine.rewind(), headers);
            journal.endExchange(id, new FastGatewayAttributes(), 1_000L, 2_000L, 200,
                    64, requestBody.capacity(), 32, responseBody.capacity(),
                    1_100L, 1_500L, 1_900L,
                    BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);
        }
    }

    private static ByteBuffer ascii(final String s)
    {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void deleteRecursively(final Path dir) throws IOException
    {
        if (!Files.exists(dir))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p ->
            {
                try
                {
                    Files.deleteIfExists(p);
                }
                catch (final IOException ignored)
                {
                    // Leftovers cost the test run disk, nothing else.
                }
            });
        }
    }
}
