package com.ethlo.r7;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.r7f.R7Tailer;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.r7f.R7fJournalProvider;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * Measures what one in-flight exchange costs the reader in retained heap.
 * <p>
 * This is the number the reassembler's tuning is really about: an exchange is held from its
 * first event until its end arrives, bounded by {@code maxAge} and {@code maxInFlight}, so at
 * FULL level with real traffic the in-flight set is what dominates a tailer's heap.
 * <p>
 * The exchanges written here deliberately have <em>no end event</em>, which is what leaves them
 * in flight for the measurement. They are fed through a real journal and a real tailer rather
 * than into the reassembler directly, because that is the only way the header strings are
 * decoded from FlatBuffer bytes the way they are in production: each of an exchange's four
 * header sets then arrives as freshly allocated strings, sharing nothing, which is exactly the
 * duplication worth measuring.
 * <p>
 * Opt-in, like the other benchmarks:
 * <pre>mvn -pl r7-journal-mmap test -Dr7.bench=true -Dtest=ReassemblerHeapBenchmarkTest</pre>
 */
@EnabledIfSystemProperty(named = "r7.bench", matches = "true")
class ReassemblerHeapBenchmarkTest
{
    private static final int EXCHANGES = 20_000;
    private static final int REQUEST_HEADERS = 18;
    private static final int FORWARDED_HEADERS = 4;
    private static final int VALUE_LENGTH = 40;
    private static final long SEGMENT_BYTES = 256L * 1024 * 1024;

    @Test
    void reportRetainedHeapPerInFlightExchange() throws Exception
    {
        final Path dir = Files.createTempDirectory("r7f-heap");
        try
        {
            writeUnfinishedExchanges(dir);

            final long before = settledHeap();

            final R7Tailer tailer = new R7Tailer(dir, Duration.ZERO, noop());
            tailer.runTick();

            final long after = settledHeap();

            // Keeps the tailer, and therefore every in-flight exchange, strongly reachable
            // across the measurement above. Without it the whole point could be collected.
            if (tailer.hashCode() == 0)
            {
                throw new IllegalStateException("unreachable, but the tailer must stay live");
            }

            final long retained = after - before;
            System.out.println();
            System.out.printf("in-flight exchanges     : %d%n", EXCHANGES);
            System.out.printf("headers per exchange    : %d request + %d forwarded + 4 response, x4 sets%n",
                    REQUEST_HEADERS, FORWARDED_HEADERS);
            System.out.printf("retained heap           : %.1f MB%n", retained / 1024.0 / 1024.0);
            System.out.printf("retained per exchange   : %.0f bytes%n", (double) retained / EXCHANGES);
            System.out.println();
        }
        finally
        {
            deleteRecursively(dir);
        }
    }

    private static void writeUnfinishedExchanges(final Path dir) throws IOException
    {
        final R7fJournalProvider provider = new R7fJournalProvider(dir, 0, SEGMENT_BYTES, false);
        try (final R7fJournal journal = new R7fJournal(provider))
        {
            final InetAddress client = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
            final ByteBuffer requestLine = ascii("GET /bench/api/v1/users HTTP/1.1");
            final ByteBuffer responseLine = ascii("HTTP/1.1 200 OK");

            // The request pair differs as it does in production: the proxy rewrites Host and
            // appends forwarding headers on the way upstream, so the two sets never pack to the
            // same bytes. The response pair is the same object twice, as on a route with no
            // response filters, where the snapshot taken at commit is what is finally sent.
            final MutableGatewayHeaders clientHeaders = requestHeaders(REQUEST_HEADERS);
            clientHeaders.add("host", "api.example.com");
            final MutableGatewayHeaders upstreamHeaders = requestHeaders(REQUEST_HEADERS);
            upstreamHeaders.add("host", "backend.internal:11111");
            for (int i = 0; i < FORWARDED_HEADERS; i++)
            {
                upstreamHeaders.add("x-forwarded-" + i, distinctValue(100 + i));
            }
            final MutableGatewayHeaders responseHeaders = new MutableFastGatewayHeaders();
            responseHeaders.add("server", "nginx");
            responseHeaders.add("date", "Wed, 17 Sep 2026 09:00:00 GMT");
            responseHeaders.add("content-type", "text/plain");
            responseHeaders.add("content-length", "2");

            for (int i = 0; i < EXCHANGES; i++)
            {
                final String id = "req-" + i;
                journal.clientRequest(JournalLevel.HEADERS, id, requestLine.rewind(), clientHeaders, client, IpSource.SOCKET);
                journal.upstreamRequest(JournalLevel.HEADERS, id, requestLine.rewind(), upstreamHeaders);
                journal.upstreamResponse(JournalLevel.HEADERS, id, 200, responseLine.rewind(), responseHeaders);
                journal.clientResponse(JournalLevel.HEADERS, id, 200, responseLine.rewind(), responseHeaders);
                // No endExchange: that is what leaves it in flight.
            }
        }
    }

    private static MutableGatewayHeaders requestHeaders(final int count)
    {
        final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();
        for (int i = 0; i < count; i++)
        {
            headers.add("x-request-header-" + i, distinctValue(i));
        }
        return headers;
    }

    /**
     * A value that differs from every other header's in the same set.
     * <p>
     * It matters that they differ: the interner would collapse a fixture whose headers all
     * carried one string into a single entry and report a saving real traffic would never see.
     * The duplication worth measuring is between the client and upstream copies of the
     * <em>same</em> header, which the two sets here still share.
     */
    private static String distinctValue(final int index)
    {
        return (index + "-" + filler(VALUE_LENGTH)).substring(0, VALUE_LENGTH);
    }

    private static String filler(final int length)
    {
        final StringBuilder sb = new StringBuilder(length);
        while (sb.length() < length)
        {
            sb.append("abcdefghijklmnopqrstuvwxyz0123456789");
        }
        return sb.substring(0, length);
    }

    private static ExchangeCompletionListener noop()
    {
        return exchange -> {
        };
    }

    /**
     * Used heap after giving the collector several chances to settle. Crude, which is why the
     * exchange count is high enough that the signal is tens of megabytes.
     */
    private static long settledHeap() throws InterruptedException
    {
        for (int i = 0; i < 4; i++)
        {
            System.gc();
            Thread.sleep(120);
        }
        final Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
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
