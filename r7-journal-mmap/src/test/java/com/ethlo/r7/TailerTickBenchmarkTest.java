package com.ethlo.r7;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.r7f.R7Tailer;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.r7f.R7fJournalProvider;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * What one tailer tick costs, idle and with one new exchange per shard, against a directory
 * holding a realistic number of retained sealed segments. The baseline for keeping active
 * segments mapped between ticks (design/live-tailing.md, step 1).
 *
 * <pre>./mvnw -pl r7-journal-mmap test -Dr7.bench=true -Dtest=TailerTickBenchmarkTest</pre>
 */
@EnabledIfSystemProperty(named = "r7.bench", matches = "true")
class TailerTickBenchmarkTest
{
    private static final int SHARDS = 2;
    private static final int SEGMENT_SIZE = 256 * 1024;
    private static final int TICKS = 20_000;

    @TempDir
    Path journalDir;

    @Test
    void tickCost() throws IOException
    {
        final R7fJournal[] journals = new R7fJournal[SHARDS];
        for (int shard = 0; shard < SHARDS; shard++)
        {
            journals[shard] = new R7fJournal(new R7fJournalProvider(journalDir, shard, SEGMENT_SIZE, false));
        }
        try
        {
            // Retained history: enough exchanges to rotate each shard's segment many times.
            long id = 0;
            while (sealedSegments() < 200)
            {
                for (final R7fJournal journal : journals)
                {
                    write(journal, "h-" + id++);
                }
            }

            final long[] delivered = new long[1];
            final ExchangeCompletionListener sink = new ExchangeCompletionListener()
            {
                @Override
                public void onComplete(final JournalExchange exchange)
                {
                    delivered[0]++;
                }
            };
            final R7Tailer tailer = new R7Tailer(journalDir, journalDir.resolve("cp"), sink,
                    com.ethlo.r7.journal.api.JournalIntegrityListener.NOOP, ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1)));
            tailer.runTick(); // catch up

            for (int round = 0; round < 3; round++)
            {
                long start = System.nanoTime();
                for (int i = 0; i < TICKS; i++)
                {
                    tailer.runTick();
                }

                final long idle = (System.nanoTime() - start) / TICKS;

                long writeNanos = 0;
                start = System.nanoTime();
                for (int i = 0; i < TICKS / 4; i++)
                {
                    final long w = System.nanoTime();
                    for (final R7fJournal journal : journals)
                    {
                        write(journal, "b-" + round + "-" + i + "-" + id++);
                    }
                    writeNanos += System.nanoTime() - w;
                    tailer.runTick();
                }
                final long busy = (System.nanoTime() - start - writeNanos) / (TICKS / 4);

                System.out.printf("round %d: %d sealed segments, idle tick %,d ns, tick with %d new exchanges %,d ns%n",
                        round, sealedSegments(), idle, SHARDS, busy);
            }
        }
        finally
        {
            for (final R7fJournal journal : journals)
            {
                journal.close();
            }
        }
    }

    private long sealedSegments() throws IOException
    {
        try (Stream<Path> s = Files.list(journalDir))
        {
            return s.filter(p -> p.getFileName().toString().endsWith(".r7f")).count();
        }
    }

    private static void write(final R7fJournal journal, final String reqId) throws IOException
    {
        journal.clientRequest(JournalLevel.HEADERS, reqId,
                ByteBuffer.wrap(("GET /" + reqId + " HTTP/1.1").getBytes(StandardCharsets.ISO_8859_1)),
                new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        journal.endExchange(reqId, new FastGatewayAttributes(),
                1L, 2L, 200, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);
    }
}
