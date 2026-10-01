package com.ethlo.r7;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalIntegrityListener;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.r7f.R7Tailer;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.r7f.R7fJournalProvider;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * Time from the end event's commit to the exchange reaching the consumer, with a tailer
 * looping on {@code awaitNewData} as the tailer apps do. One writer thread, a gap between
 * exchanges so the tailer goes idle and has to be woken each time.
 *
 * <pre>./mvnw -pl r7-journal-mmap test -Dr7.bench=true -Dtest=TailerLatencyBenchmarkTest</pre>
 */
@EnabledIfSystemProperty(named = "r7.bench", matches = "true")
class TailerLatencyBenchmarkTest
{
    private static final int EXCHANGES = 20_000;

    @TempDir
    Path journalDir;

    @Test
    void commitToDeliveryLatency() throws Exception
    {
        for (final long gapMicros : new long[]{0, 50, 1_000})
        {
            run(gapMicros);
        }
    }

    private void run(final long gapMicros) throws Exception
    {
        final Path dir = java.nio.file.Files.createDirectories(journalDir.resolve("gap-" + gapMicros));
        final ConcurrentHashMap<String, Long> committedAt = new ConcurrentHashMap<>();
        final long[] latencies = new long[EXCHANGES];
        final int[] count = new int[1];
        final ExchangeCompletionListener sink = new ExchangeCompletionListener()
        {
            @Override
            public void onComplete(final JournalExchange exchange)
            {
                final Long at = committedAt.get(exchange.getRequestId());
                if (at != null && count[0] < latencies.length)
                {
                    latencies[count[0]++] = System.nanoTime() - at;
                }
            }
        };

        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(dir, 0, 8 * 1024 * 1024, false)))
        {
            final R7Tailer tailer = new R7Tailer(dir, dir.resolve("cp"), sink, JournalIntegrityListener.NOOP,
                    ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1)));
            final AtomicBoolean done = new AtomicBoolean();
            final Thread reader = Thread.ofPlatform().start(() ->
            {
                try
                {
                    while (!done.get() || count[0] < EXCHANGES)
                    {
                        tailer.runTick();
                        tailer.awaitNewData(Duration.ofMillis(100));
                    }
                }
                catch (final Exception e)
                {
                    throw new RuntimeException(e);
                }
            });

            for (int i = 0; i < EXCHANGES; i++)
            {
                final String reqId = "r-" + i;
                journal.clientRequest(JournalLevel.HEADERS, reqId,
                        ByteBuffer.wrap(("GET /" + reqId + " HTTP/1.1").getBytes(StandardCharsets.ISO_8859_1)),
                        new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
                committedAt.put(reqId, System.nanoTime());
                journal.endExchange(reqId, new FastGatewayAttributes(),
                        1L, 2L, 200, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                        BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);
                if (gapMicros > 0)
                {
                    LockSupport.parkNanos(gapMicros * 1_000L);
                }
            }
            done.set(true);
            reader.join(60_000);
        }

        final long[] sorted = Arrays.copyOf(latencies, count[0]);
        Arrays.sort(sorted);
        System.out.printf("gap %5d µs: %d delivered, latency p50 %,d ns, p99 %,d ns, p99.9 %,d ns, max %,d ns%n",
                gapMicros, sorted.length, pct(sorted, 50), pct(sorted, 99), pct(sorted, 99.9), sorted[sorted.length - 1]);
    }

    private static long pct(final long[] sorted, final double p)
    {
        return sorted[(int) Math.min(sorted.length - 1, Math.floor(sorted.length * p / 100.0))];
    }
}
