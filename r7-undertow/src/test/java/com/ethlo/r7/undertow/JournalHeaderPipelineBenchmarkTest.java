package com.ethlo.r7.undertow;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.ethlo.r7.ShardedJournalWriter;
import com.ethlo.r7.api.CompletedGatewayExchange;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.GatewayResponse;
import com.ethlo.r7.api.GatewayRouteInfo;
import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.api.MutableGatewayAttributes;
import com.ethlo.r7.api.StateKey;
import com.ethlo.r7.config.JournalDirectionConfig;
import com.ethlo.r7.config.RouteJournalConfig;
import com.ethlo.r7.journal.JournalSecurity;
import com.ethlo.r7.journal.StatefulJournal;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.r7f.R7fJournalProvider;
import com.ethlo.r7.util.FastGatewayAttributes;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;

/**
 * Measures the journal header pipeline in isolation: {@link StatefulJournal} redaction into
 * {@link R7fJournal} FlatBuffer encoding into the mmap segment.
 * <p>
 * Scope, and what the numbers therefore do <em>not</em> say: there is no socket, no Undertow
 * exchange and no upstream here, so this is not end-to-end gateway throughput. It is the cost
 * of journaling one exchange's worth of headers, which is the part the header work actually
 * lives in. Compare runs of this test against each other, never against the wrk numbers.
 * <p>
 * The client-side and upstream-side request headers are deliberately built as production
 * builds them — an {@link ImmutableHeaderSnapshot} taken at ingress and an
 * {@link UndertowGatewayHeaders} over the same live {@link HeaderMap}. They therefore share
 * String references for every header a filter did not touch, which is the relationship any
 * future delta encoding would exploit.
 * <p>
 * Opt-in, because it writes real segments and takes a while:
 * <pre>mvn -pl r7-undertow test -Dr7.bench=true -Dtest=JournalHeaderPipelineBenchmarkTest</pre>
 */
@EnabledIfSystemProperty(named = "r7.bench", matches = "true")
final class JournalHeaderPipelineBenchmarkTest
{
    /**
     * Bytes each scenario aims to journal while measured. Iteration counts are derived from
     * it rather than fixed, so that a scenario writing 16KB per exchange does not write
     * twenty times the segments of one writing 800 bytes — segment rotation waits on the
     * warmer thread, and a benchmark that rotates is partly measuring the warmer.
     */
    private static final long MEASURED_BYTE_BUDGET = 192L * 1024 * 1024;
    private static final int MIN_MEASURED_EXCHANGES = 20_000;
    private static final int MAX_MEASURED_EXCHANGES = 200_000;

    /**
     * Comfortably larger than the budget plus its warmup, so no scenario rotates a segment
     * and the measured loop never waits for one to be warmed.
     */
    private static final long SEGMENT_BYTES = 512L * 1024 * 1024;

    /** Request-id length matching the generator's output, so scratch copying is realistic. */
    private static final int REQUEST_ID_LENGTH = 16;
    private static final int REQUEST_ID_POOL = 1024;

    /**
     * Header names known to the redactor as safe, drawn from the real policy so that this
     * test keeps measuring what the policy actually does if the policy changes.
     */
    private static final List<String> SAFE_NAMES = JournalSecurity.SAFE_REQUEST_HEADERS.names().stream()
            .sorted()
            .toList();

    /**
     * Realistic names that are absent from the safe set, and are therefore fingerprinted with
     * SHA-256 on every journaled message. Filtered against the policy rather than assumed, so
     * that a name later added to the safe list fails loudly here instead of quietly turning
     * this into a different benchmark.
     */
    private static final List<String> UNSAFE_NAMES = Stream.of(
                    "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site", "sec-fetch-user",
                    "upgrade-insecure-requests", "cookie", "referer", "authorization",
                    "x-api-key", "x-session-token", "x-trace-token", "x-tenant-id",
                    "x-device-id", "x-client-version", "x-experiment", "x-feature-flags",
                    "x-internal-1", "x-internal-2", "x-internal-3", "x-internal-4")
            .filter(n -> !JournalSecurity.SAFE_REQUEST_HEADERS.contains(n))
            .toList();

    private record Scenario(String name,
                            int headerCount,
                            int valueLength,
                            int unsafeCount,
                            JournalLevel level,
                            int bodyBytes,
                            int threads,
                            int shards,
                            int upstreamHeaderCount)
    {
        Scenario(String name, int headerCount, int valueLength, int unsafeCount, JournalLevel level, int bodyBytes)
        {
            this(name, headerCount, valueLength, unsafeCount, level, bodyBytes, 1, 1, headerCount);
        }

        /**
         * Sizes the upstream-request entry. {@code -1} omits it entirely, which is the only
         * thing left to compare against now that the entry is journaled as a difference: it
         * says what keeping the forwarded-request record actually costs.
         */
        Scenario withUpstreamHeaders(final String suffix, final int upstreamHeaderCount)
        {
            return new Scenario(name + suffix, headerCount, valueLength, unsafeCount, level, bodyBytes,
                    threads, shards, upstreamHeaderCount);
        }

        Scenario withThreads(final String suffix, final int threads)
        {
            return new Scenario(name + suffix, headerCount, valueLength, unsafeCount, level, bodyBytes,
                    threads, shards, upstreamHeaderCount);
        }

        Scenario withShards(final String suffix, final int shards)
        {
            return new Scenario(name + suffix, headerCount, valueLength, unsafeCount, level, bodyBytes,
                    threads, shards, upstreamHeaderCount);
        }
    }

    private record Result(Scenario scenario,
                          long exchanges,
                          long elapsedNanos,
                          long journalBytes,
                          long allocatedBytes)
    {
        double nanosPerExchange()
        {
            return (double) elapsedNanos / exchanges;
        }

        double exchangesPerSecond()
        {
            return exchanges / (elapsedNanos / 1_000_000_000.0);
        }

        double journalBytesPerExchange()
        {
            return (double) journalBytes / exchanges;
        }

        double allocatedBytesPerExchange()
        {
            return allocatedBytes < 0 ? Double.NaN : (double) allocatedBytes / exchanges;
        }
    }

    private static final List<Scenario> SCENARIOS = List.of(
            // The floor: headers are never encoded at METADATA, so this is everything the
            // pipeline costs when the header work is removed entirely.
            new Scenario("metadata-floor", 18, 40, 7, JournalLevel.METADATA, 0),

            // The wrk workload's shape: 18 browser headers, 7 of them outside the safe set.
            new Scenario("browser-realistic", 18, 40, 7, JournalLevel.HEADERS, 0),

            // Same shape with redaction removed and with it applied to everything. The spread
            // between these three is the fingerprinting cost.
            new Scenario("browser-all-safe", 18, 40, 0, JournalLevel.HEADERS, 0),
            new Scenario("browser-all-unsafe", 18, 40, 18, JournalLevel.HEADERS, 0),

            // Header count axis, redaction ratio held near the realistic one.
            new Scenario("headers-few", 4, 40, 2, JournalLevel.HEADERS, 0),
            new Scenario("headers-many", 40, 40, 15, JournalLevel.HEADERS, 0),

            // Value length axis, header count held at the realistic one.
            new Scenario("values-long", 18, 400, 7, JournalLevel.HEADERS, 0),

            new Scenario("browser-full-body", 18, 40, 7, JournalLevel.FULL, 256),

            // Contention axis. One R7fJournal, several writers — the shape a single-shard
            // deployment presents, and the only place the size of the journal's critical
            // section is visible at all.
            new Scenario("browser-realistic", 18, 40, 7, JournalLevel.HEADERS, 0).withThreads("-t4", 4),
            new Scenario("browser-realistic", 18, 40, 7, JournalLevel.HEADERS, 0).withThreads("-t8", 8),

            // Sharding axis, at the thread count where contention actually shows. Whether
            // these beat the single-shard row above is the whole question behind the
            // shard_count default.
            new Scenario("browser-realistic", 18, 40, 7, JournalLevel.HEADERS, 0)
                    .withThreads("-t8", 8).withShards("-s2", 2),
            new Scenario("browser-realistic", 18, 40, 7, JournalLevel.HEADERS, 0)
                    .withThreads("-t8", 8).withShards("-s4", 4),
            new Scenario("browser-realistic", 18, 40, 7, JournalLevel.HEADERS, 0)
                    .withThreads("-t8", 8).withShards("-s8", 8),

            // What the upstream-request entry still costs now that it is journaled as a
            // difference rather than a second full copy: the gap between this row and
            // browser-realistic is the price of keeping that record at all.
            new Scenario("browser-realistic", 18, 40, 7, JournalLevel.HEADERS, 0)
                    .withUpstreamHeaders("-noupreq", -1)
    );

    @Test
    void measureHeaderPipeline() throws IOException
    {
        final List<Result> results = new ArrayList<>();
        for (final Scenario scenario : SCENARIOS)
        {
            results.add(run(scenario));
        }
        printTable(results);
    }

    private Result run(final Scenario scenario) throws IOException
    {
        final Path dir = Files.createTempDirectory("r7-header-bench");
        try
        {
            // Each shard gets its own slice of the budget, so that adding shards changes what
            // is being measured rather than how much is pre-allocated for it.
            final long segmentBytes = Math.max(SEGMENT_BYTES / scenario.shards(), 64L * 1024 * 1024);
            final ShardedJournalWriter<R7fJournal> writer = new ShardedJournalWriter<>(scenario.shards(),
                    // Sealed segments are deleted as they retire, exactly as a tailer would, so
                    // that disk usage stays bounded no matter how many exchanges are measured.
                    shard -> new R7fJournal(new R7fJournalProvider(dir, shard, segmentBytes, true),
                            JournalHeaderPipelineBenchmarkTest::deleteQuietly));
            try
            {
                final int perThread = measuredExchanges(scenario) / scenario.threads();
                final int measured = perThread * scenario.threads();

                // Buffers are consumed as they are journaled, so a writer needs its own.
                final List<Fixture> fixtures = new ArrayList<>();
                for (int i = 0; i < scenario.threads(); i++)
                {
                    fixtures.add(new Fixture(scenario));
                }

                runConcurrently(writer, fixtures, perThread / 4);

                final List<Path> segmentsBefore = activeSegments(writer, scenario.shards());
                final Totals totals = runConcurrently(writer, fixtures, perThread);

                if (!segmentsBefore.equals(activeSegments(writer, scenario.shards())))
                {
                    throw new IllegalStateException("Scenario " + scenario.name() + " rotated a segment while"
                            + " being measured, so its timing includes waiting for the warmer thread. Raise"
                            + " SEGMENT_BYTES or lower MEASURED_BYTE_BUDGET.");
                }

                return new Result(scenario, measured, totals.elapsedNanos(), totals.journalBytes(), totals.allocatedBytes());
            }
            finally
            {
                writer.shutdown();
            }
        }
        finally
        {
            deleteRecursively(dir);
        }
    }

    private static List<Path> activeSegments(final ShardedJournalWriter<R7fJournal> writer, final int shards)
    {
        final List<Path> paths = new ArrayList<>(shards);
        for (int i = 0; i < shards; i++)
        {
            paths.add(writer.getJournal(shardProbe(i, shards)).getActivePath());
        }
        return paths;
    }

    /**
     * A request id that {@link ShardedJournalWriter} routes to the given shard, so the shards
     * can be enumerated through the same mapping the benchmark writes through.
     */
    private static String shardProbe(final int shard, final int shards)
    {
        for (int candidate = 0; ; candidate++)
        {
            final String id = "probe-" + candidate;
            final int h = id.hashCode();
            if (((h ^ (h >>> 16)) & (shards - 1)) == shard)
            {
                return id;
            }
        }
    }

    /**
     * Iterations that keep this scenario inside {@link #MEASURED_BYTE_BUDGET}, derived from a
     * deliberately generous estimate of its per-exchange size. Over-estimating costs
     * iterations; under-estimating costs a segment rotation in the middle of a measurement.
     */
    private static int measuredExchanges(final Scenario scenario)
    {
        // Headers are not encoded below HEADERS level, so only the framing and EndExchange
        // remain.
        final long estimate = scenario.level() == JournalLevel.METADATA
                ? 1_024
                // Two request messages carry the full header set; the two response messages
                // carry the small fixed response set.
                : 2L * scenario.headerCount() * (44 + scenario.valueLength())
                + 2L * 4 * 84
                + 2L * scenario.bodyBytes()
                + 400;

        final long derived = MEASURED_BYTE_BUDGET / estimate;
        return (int) Math.clamp(derived, MIN_MEASURED_EXCHANGES, MAX_MEASURED_EXCHANGES);
    }

    private record Totals(long elapsedNanos, long journalBytes, long allocatedBytes)
    {
    }

    /**
     * Runs one writer per fixture against the same journal and times them as a group.
     * <p>
     * The elapsed time is wall clock across all writers, so {@code ns/exch} is the aggregate
     * cost of an exchange rather than one writer's — which is what makes the journal's
     * critical section visible: perfect scaling halves it when the writers double, and a fully
     * serialised write path leaves it flat.
     */
    private Totals runConcurrently(final ShardedJournalWriter<R7fJournal> writer, final List<Fixture> fixtures, final int exchangesPerThread)
    {
        final int threads = fixtures.size();
        final long[] bytes = new long[threads];
        final long[] allocated = new long[threads];
        final Thread[] writers = new Thread[threads];
        final CountDownLatch ready = new CountDownLatch(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int t = 0; t < threads; t++)
        {
            final int index = t;
            final Fixture fixture = fixtures.get(t);
            writers[t] = new Thread(() ->
            {
                try
                {
                    ready.countDown();
                    start.await();
                    final long allocBefore = allocatedBytes();
                    long written = 0;
                    for (int i = 0; i < exchangesPerThread; i++)
                    {
                        written += fixture.exchange(writer, i);
                    }
                    final long allocAfter = allocatedBytes();
                    bytes[index] = written;
                    allocated[index] = (allocBefore < 0 || allocAfter < 0) ? -1 : allocAfter - allocBefore;
                }
                catch (final Throwable e)
                {
                    failure.compareAndSet(null, e);
                }
            }, "bench-writer-" + t);
            writers[t].start();
        }

        final long elapsed;
        try
        {
            ready.await();
            final long begin = System.nanoTime();
            start.countDown();
            for (final Thread thread : writers)
            {
                thread.join();
            }
            elapsed = System.nanoTime() - begin;
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while benchmarking", e);
        }

        if (failure.get() != null)
        {
            throw new IllegalStateException("A benchmark writer failed", failure.get());
        }

        long totalBytes = 0;
        long totalAllocated = 0;
        for (int t = 0; t < threads; t++)
        {
            totalBytes += bytes[t];
            totalAllocated = totalAllocated < 0 || allocated[t] < 0 ? -1 : totalAllocated + allocated[t];
        }
        return new Totals(elapsed, totalBytes, totalAllocated);
    }

    /**
     * Everything one scenario needs, built once so that the measured loop allocates only what
     * the pipeline itself allocates.
     */
    private static final class Fixture
    {
        private final Scenario scenario;
        private final RouteJournalConfig config;
        private final CompletedGatewayExchange exchange;
        private final InetAddress clientAddress;

        private final GatewayHeaders clientRequestHeaders;
        private final GatewayHeaders upstreamRequestHeaders;
        private final GatewayHeaders upstreamResponseHeaders;
        private final GatewayHeaders clientResponseHeaders;

        private final ByteBuffer requestLine;
        private final ByteBuffer responseLine;
        private final ByteBuffer requestBody;
        private final ByteBuffer responseBody;
        private final String[] requestIds;
        private final MutableGatewayAttributes attributes;

        private Fixture(final Scenario scenario)
        {
            this.scenario = scenario;
            final JournalDirectionConfig direction = new JournalDirectionConfig(scenario.level(), null);
            this.config = new RouteJournalConfig(direction, direction);
            this.exchange = new StubExchange();

            try
            {
                this.clientAddress = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
            }
            catch (final java.net.UnknownHostException e)
            {
                throw new IllegalStateException(e);
            }

            final HeaderMap requestHeaders = buildRequestHeaders(scenario);
            // As in production: the client view is the ingress snapshot, the upstream view is
            // the live map. Untouched headers are the same String instances in both.
            this.clientRequestHeaders = new ImmutableHeaderSnapshot(requestHeaders);
            this.upstreamRequestHeaders = scenario.upstreamHeaderCount() == scenario.headerCount()
                    ? new UndertowGatewayHeaders(requestHeaders)
                    : new UndertowGatewayHeaders(buildUpstreamSubset(scenario));

            final HeaderMap responseHeaders = buildResponseHeaders();
            this.upstreamResponseHeaders = new ImmutableHeaderSnapshot(responseHeaders);
            this.clientResponseHeaders = new UndertowGatewayHeaders(responseHeaders);

            this.requestLine = ascii("GET /bench/api/v1/users HTTP/1.1");
            this.responseLine = ascii("HTTP/1.1 200 OK");
            this.requestBody = ByteBuffer.wrap(filler(scenario.bodyBytes()).getBytes(StandardCharsets.ISO_8859_1));
            this.responseBody = ByteBuffer.wrap(filler(scenario.bodyBytes()).getBytes(StandardCharsets.ISO_8859_1));

            this.requestIds = new String[REQUEST_ID_POOL];
            for (int i = 0; i < REQUEST_ID_POOL; i++)
            {
                final StringBuilder sb = new StringBuilder(REQUEST_ID_LENGTH);
                sb.append(Integer.toHexString(i));
                while (sb.length() < REQUEST_ID_LENGTH)
                {
                    sb.append('0');
                }
                requestIds[i] = sb.toString();
            }

            this.attributes = new FastGatewayAttributes();
            attributes.add("route.id", "bench-passthrough");
        }

        private long exchange(final ShardedJournalWriter<R7fJournal> writer, final int iteration)
        {
            // Per exchange in production, and its flush-state flags make that load-bearing.
            final String requestId = requestIds[iteration & (REQUEST_ID_POOL - 1)];
            final StatefulJournal stateful = new StatefulJournal(writer.getJournal(requestId), config, exchange);

            stateful.clientRequest(scenario.level(), requestId, requestLine.rewind(),
                    clientRequestHeaders, clientAddress, IpSource.SOCKET);

            if (scenario.bodyBytes() > 0)
            {
                stateful.requestBody(requestId, requestBody.rewind());
            }

            if (scenario.upstreamHeaderCount() >= 0)
            {
                stateful.upstreamRequest(scenario.level(), requestId, requestLine.rewind(), upstreamRequestHeaders, null);
            }
            stateful.upstreamResponse(scenario.level(), requestId, 200, responseLine.rewind(), upstreamResponseHeaders);

            if (scenario.bodyBytes() > 0)
            {
                stateful.responseBody(requestId, responseBody.rewind());
            }

            stateful.clientResponse(scenario.level(), requestId, 200, responseLine.rewind(), clientResponseHeaders, null);
            stateful.endExchange(requestId, attributes, 1_000L, 2_000L, 200,
                    512, scenario.bodyBytes(), 128, scenario.bodyBytes(),
                    1_100L, 1_500L, 1_900L,
                    BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);

            return stateful.getBytesWritten();
        }
    }

    private static HeaderMap buildRequestHeaders(final Scenario scenario)
    {
        if (scenario.unsafeCount() > scenario.headerCount())
        {
            throw new IllegalArgumentException("unsafeCount exceeds headerCount in " + scenario.name());
        }
        final int safeCount = scenario.headerCount() - scenario.unsafeCount();
        if (safeCount > SAFE_NAMES.size() || scenario.unsafeCount() > UNSAFE_NAMES.size())
        {
            throw new IllegalArgumentException("Not enough distinct header names for " + scenario.name());
        }

        final HeaderMap map = new HeaderMap();
        for (int i = 0; i < safeCount; i++)
        {
            map.add(HttpString.tryFromString(SAFE_NAMES.get(i)), distinctValue(i, scenario.valueLength()));
        }
        for (int i = 0; i < scenario.unsafeCount(); i++)
        {
            map.add(HttpString.tryFromString(UNSAFE_NAMES.get(i)), distinctValue(100 + i, scenario.valueLength()));
        }
        return map;
    }

    /**
     * A value of the requested length that differs from every other header's.
     * <p>
     * It matters that these differ. Anything that deduplicates values — the fingerprint memo on
     * the write side, the interner on the read side — would collapse a fixture whose headers
     * all carried one string into a single entry, and report a saving that real traffic, where
     * each header carries its own value, would never see.
     */
    private static String distinctValue(final int index, final int length)
    {
        final String body = index + "-" + filler(Math.max(length, 8));
        return body.substring(0, length);
    }

    /**
     * The headers a proxy actually changes on the way upstream: the rewritten Host plus the
     * forwarding set. This is what an upstream-request entry would carry if it recorded the
     * difference rather than repeating the whole set.
     */
    private static HeaderMap buildUpstreamSubset(final Scenario scenario)
    {
        final HeaderMap map = new HeaderMap();
        final int count = Math.max(scenario.upstreamHeaderCount(), 0);
        for (int i = 0; i < count; i++)
        {
            map.add(HttpString.tryFromString(i == 0 ? "host" : "x-forwarded-" + i),
                    distinctValue(200 + i, scenario.valueLength()));
        }
        return map;
    }

    private static HeaderMap buildResponseHeaders()
    {
        final HeaderMap map = new HeaderMap();
        map.add(HttpString.tryFromString("server"), "nginx");
        map.add(HttpString.tryFromString("date"), "Tue, 16 Sep 2026 18:48:44 GMT");
        map.add(HttpString.tryFromString("content-type"), "text/plain");
        map.add(HttpString.tryFromString("content-length"), "2");
        return map;
    }

    private static String filler(final int length)
    {
        final StringBuilder sb = new StringBuilder(length);
        while (sb.length() < length)
        {
            sb.append("abcdefghijklmnopqrstuvwxyz0123456789");
        }
        return sb.substring(0, Math.max(length, 0));
    }

    private static ByteBuffer ascii(final String s)
    {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * Bytes this thread has allocated, or -1 where the JVM does not expose it. Allocation is
     * the metric several of the pipeline's costs show up in most directly — the redaction
     * copy, the lowercase per header name and the fingerprint's two strings are all invisible
     * to a wall-clock number that is dominated by whatever else the machine is doing.
     */
    private static long allocatedBytes()
    {
        if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported())
        {
            return bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        }
        return -1;
    }

    private static void printTable(final List<Result> results)
    {
        final String header = String.format(
                "%-28s %8s %8s %8s %-9s %7s %6s %9s %10s %12s %12s %12s",
                "scenario", "headers", "val-len", "unsafe", "level", "threads", "shards", "exchanges", "ns/exch", "exch/s", "bytes/exch", "alloc/exch");
        System.out.println();
        System.out.println(header);
        System.out.println("-".repeat(header.length()));
        for (final Result r : results)
        {
            System.out.printf(
                    "%-28s %8d %8d %8d %-9s %7d %6d %9d %10.0f %12.0f %12.0f %12.0f%n",
                    r.scenario().name(),
                    r.scenario().headerCount(),
                    r.scenario().valueLength(),
                    r.scenario().unsafeCount(),
                    r.scenario().level(),
                    r.scenario().threads(),
                    r.scenario().shards(),
                    r.exchanges(),
                    r.nanosPerExchange(),
                    r.exchangesPerSecond(),
                    r.journalBytesPerExchange(),
                    r.allocatedBytesPerExchange());
        }
        System.out.println();
        System.out.println("No socket and no upstream: comparable across runs of this test only.");
    }

    private static void deleteQuietly(final Path path)
    {
        try
        {
            Files.deleteIfExists(path);
        }
        catch (final IOException ignored)
        {
            // A segment left behind costs the benchmark disk, nothing else.
        }
    }

    private static void deleteRecursively(final Path dir) throws IOException
    {
        if (!Files.exists(dir))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(JournalHeaderPipelineBenchmarkTest::deleteQuietly);
        }
    }

    /**
     * Only {@code clientResponse().status()} is consulted, by the level resolution in
     * {@link StatefulJournal}. The rest is present because the interface requires it.
     */
    private static final class StubExchange implements CompletedGatewayExchange
    {
        private final GatewayResponse response = new StubResponse();

        @Override
        public String requestId()
        {
            return "bench";
        }

        @Override
        public MutableGatewayAttributes attributes()
        {
            return new FastGatewayAttributes();
        }

        @Override
        public GatewayRouteInfo route()
        {
            return null;
        }

        @Override
        public boolean isShortCircuited()
        {
            return false;
        }

        @Override
        public <T> void setAttachment(final StateKey<T> key, final T value)
        {
        }

        @Override
        public <T> T getAttachment(final StateKey<T> key)
        {
            return null;
        }

        @Override
        public GatewayRequest clientRequest()
        {
            return null;
        }

        @Override
        public GatewayResponse clientResponse()
        {
            return response;
        }

        @Override
        public GatewayRequest upstreamRequest()
        {
            return null;
        }

        @Override
        public GatewayResponse upstreamResponse()
        {
            return response;
        }

        @Override
        public boolean wasProxied()
        {
            return true;
        }
    }

    private static final class StubResponse implements GatewayResponse
    {
        @Override
        public GatewayHeaders headers()
        {
            return null;
        }

        @Override
        public int status()
        {
            return 200;
        }
    }
}
