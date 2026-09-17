package com.ethlo.r7;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.r7f.R7Tailer;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.r7f.R7fJournalProvider;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * The forwarded request and the returned response are journaled as differences from the
 * received request and the upstream response. The only thing that makes that acceptable is
 * that it is lossless, so this asserts it over the whole path: diff, FlatBuffer encoding,
 * segment, decode, reconstruction.
 * <p>
 * The shapes are generated rather than hand-picked. The diff is a heuristic tuned for what a
 * gateway actually does — replace a value, append a header, occasionally drop one — and a test
 * that only fed it those shapes would confirm the tuning rather than the property. Correctness
 * must not depend on the heuristic matching well: a poor match costs bytes, never fidelity, and
 * the only way to see that is to feed it matches it was not designed for.
 */
class HeaderDeltaRoundTripTest
{
    private static final int EXCHANGES = 300;
    private static final long SEGMENT_BYTES = 64L * 1024 * 1024;

    @Test
    void everyDeltaRebuildsExactlyTheHeadersItWasBuiltFrom() throws Exception
    {
        final Random random = new Random(20260917L);
        final Path dir = Files.createTempDirectory("r7f-delta");
        try
        {
            final Map<String, List<String>> expectedUpstreamRequest = new HashMap<>();
            final Map<String, List<String>> expectedClientResponse = new HashMap<>();

            final R7fJournalProvider provider = new R7fJournalProvider(dir, 0, SEGMENT_BYTES, false);
            try (final R7fJournal journal = new R7fJournal(provider))
            {
                final InetAddress client = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
                final ByteBuffer requestLine = ascii("GET /delta HTTP/1.1");
                final ByteBuffer responseLine = ascii("HTTP/1.1 200 OK");

                for (int i = 0; i < EXCHANGES; i++)
                {
                    final String id = "delta-" + i;

                    final GatewayHeaders clientRequest = randomHeaders(random, "req");
                    final GatewayHeaders upstreamRequest = mutate(random, clientRequest);
                    final GatewayHeaders upstreamResponse = randomHeaders(random, "res");
                    final GatewayHeaders clientResponse = mutate(random, upstreamResponse);

                    expectedUpstreamRequest.put(id, entries(upstreamRequest));
                    expectedClientResponse.put(id, entries(clientResponse));

                    journal.clientRequest(JournalLevel.HEADERS, id, requestLine.rewind(), clientRequest, client, IpSource.SOCKET);
                    journal.upstreamRequest(JournalLevel.HEADERS, id, requestLine.rewind(), upstreamRequest, clientRequest);
                    journal.upstreamResponse(JournalLevel.HEADERS, id, 200, responseLine.rewind(), upstreamResponse);
                    journal.clientResponse(JournalLevel.HEADERS, id, 200, responseLine.rewind(), clientResponse, upstreamResponse);
                    journal.endExchange(id, new FastGatewayAttributes(), 1L, 2L, 200,
                            0, 0, 0, 0, 1L, 1L, 2L,
                            BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);
                }
            }

            final Map<String, JournalExchange> read = new HashMap<>();
            final ExchangeCompletionListener collector = exchange -> read.put(exchange.getRequestId(), exchange);
            new R7Tailer(dir, Duration.ZERO, collector).runTick();

            assertThat(read).as("every exchange came back").hasSize(EXCHANGES);

            for (final Map.Entry<String, JournalExchange> entry : read.entrySet())
            {
                final String id = entry.getKey();
                final JournalExchange exchange = entry.getValue();

                assertThat(entries(exchange.getUpstreamRequestHeaders()))
                        .as("upstream request headers of %s", id)
                        .containsExactlyElementsOf(expectedUpstreamRequest.get(id));

                assertThat(entries(exchange.getClientResponseHeaders()))
                        .as("client response headers of %s", id)
                        .containsExactlyElementsOf(expectedClientResponse.get(id));
            }
        }
        finally
        {
            deleteRecursively(dir);
        }
    }

    /**
     * A header set the gateway might have received. Includes repeats, because multiplicity is
     * part of what has to survive, and an occasional empty set, because that is the boundary
     * where a delta has nothing to refer to.
     */
    private static GatewayHeaders randomHeaders(final Random random, final String prefix)
    {
        final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();
        final int count = random.nextInt(13);
        for (int i = 0; i < count; i++)
        {
            final String name = prefix + "-" + random.nextInt(8);
            headers.add(name, "v" + random.nextInt(6));
        }
        return headers;
    }

    /**
     * What a filter chain and a proxy might do to a set on the way through: leave entries alone,
     * change values, insert, drop, append. Deliberately more disruptive than reality so the diff
     * is exercised past the shapes it was designed around.
     */
    private static GatewayHeaders mutate(final Random random, final GatewayHeaders source)
    {
        final MutableGatewayHeaders result = new MutableFastGatewayHeaders();
        for (final String entry : entries(source))
        {
            final int split = entry.indexOf('=');
            final String name = entry.substring(0, split);
            final String value = entry.substring(split + 1);

            switch (random.nextInt(10))
            {
                case 0 -> { /* dropped */ }
                case 1 -> result.add(name, value + "-rewritten");
                case 2 ->
                {
                    result.add("x-inserted-" + random.nextInt(3), "i" + random.nextInt(4));
                    result.add(name, value);
                }
                default -> result.add(name, value);
            }
        }
        final int appended = random.nextInt(4);
        for (int i = 0; i < appended; i++)
        {
            result.add("x-forwarded-" + i, "f" + random.nextInt(4));
        }
        return result;
    }

    private static List<String> entries(final GatewayHeaders headers)
    {
        final List<String> out = new ArrayList<>();
        if (headers != null)
        {
            headers.forEach((name, value) -> out.add(name + "=" + value));
        }
        return out;
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
