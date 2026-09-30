package com.ethlo.r7.undertow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpServer;

/**
 * What one proxied request costs the gateway's XNIO I/O threads: CPU time, which is what
 * throughput is made of, and bytes allocated, which is held to a budget.
 * <p>
 * CPU time per request is the number that matters, and it is reported for every scenario. It is
 * not gated: it depends on the machine and on what else runs, so a threshold would be either
 * flaky or too loose to catch anything. Compare it between commits on one machine.
 * <p>
 * Allocation is gated, but not because it is expensive: short-lived garbage on this path is a
 * TLAB bump and dies young, and GC does not register in a CPU profile of passthrough (see
 * design/server-spi.md, "Measured"). It is gated because it is deterministic enough for CI
 * (within about 2% between runs, where CPU time varies by a factor of two) and it moves when a
 * layer of per-request objects is added. The headroom is about 900 bytes, so it catches a new
 * wrapper graph, not a single stray lambda; the CPU number and a profile catch the rest.
 * <p>
 * Only the gateway's I/O threads are measured. In these scenarios nothing dispatches to a virtual
 * thread, the Undertow proxy client runs its upstream connections on the same I/O thread as the
 * inbound exchange, and journal writes happen synchronously on the calling thread - so all the
 * request path's work lands there, while the load generator's and the backend's does not.
 * <p>
 * The budgets are the measured values with headroom for JIT variation, not targets. When a change
 * lowers a number for good, lower its budget in the same PR so the gain cannot be lost again.
 */
class RequestPathCostTest
{
    private static final Logger logger = LoggerFactory.getLogger(RequestPathCostTest.class);

    /**
     * XNIO names I/O threads {@code XNIO-<worker> I/O-<n>}.
     */
    private static final Pattern XNIO_IO_THREAD = Pattern.compile("XNIO-\\d+ I/O-\\d+");

    // Enough for C2 to compile the path, and for escape analysis to have had its say, before
    // anything is counted: an interpreted request allocates far more than a compiled one.
    private static final int WARMUP_REQUESTS = 30_000;
    private static final int MEASURED_REQUESTS = 30_000;
    private static final int CONCURRENCY = 8;

    // Bytes per request; see the class comment for how they are set and maintained. Measured on
    // 2026-09-30 (JDK 25.0.4): passthrough 8237-8510, journal HEADERS 9368-9509, filtered
    // 9233-9606 locally; the GitHub runner measured about 2-4% higher. About 10% headroom:
    // enough for JIT variation and the runner, too little for a new per-request object graph to
    // slip through unnoticed.
    //
    // The journal budget rose by ~800 bytes with #97, deliberately: redacting each header set
    // once into an array snapshot allocates those arrays and saves ~14k instructions per request.
    // Allocation that buys instructions back is the right trade; this gate is there to make it a
    // visible one.
    private static final long PASSTHROUGH_BUDGET = 9_400;
    private static final long JOURNAL_HEADERS_BUDGET = 10_400;
    private static final long FILTERED_BUDGET = 10_600;

    @TempDir
    static Path dir;

    private static HttpServer backend;
    private static R7Main gateway;
    private static HttpClient client;
    private static int gatewayPort;
    private static long[] ioThreadIds;

    @BeforeAll
    static void start() throws Exception
    {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final byte[] ok = "OK".getBytes(StandardCharsets.US_ASCII);
        backend.createContext("/", exchange ->
        {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream out = exchange.getResponseBody())
            {
                out.write(ok);
            }
        });
        backend.setExecutor(Executors.newFixedThreadPool(CONCURRENCY));
        backend.start();

        final Set<Long> existingIoThreads = ioThreadIds(new HashSet<>());
        gatewayPort = freePort();
        gateway = new R7Main(writeRoutes("http://127.0.0.1:" + backend.getAddress().getPort()), writeServer(gatewayPort, freePort()));
        ioThreadIds = ioThreadIds(existingIoThreads).stream().mapToLong(Long::longValue).toArray();
        assertThat(ioThreadIds).as("gateway XNIO I/O threads").isNotEmpty();

        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterAll
    static void stop()
    {
        if (gateway != null)
        {
            gateway.stop();
        }
        if (backend != null)
        {
            backend.stop(0);
        }
        if (client != null)
        {
            client.close();
        }
    }

    @Test
    void passthrough() throws Exception
    {
        assertWithinBudget("passthrough", "/bench", PASSTHROUGH_BUDGET);
    }

    @Test
    void journalHeaders() throws Exception
    {
        assertWithinBudget("journal HEADERS", "/journal", JOURNAL_HEADERS_BUDGET);
    }

    @Test
    void filtered() throws Exception
    {
        assertWithinBudget("filtered", "/filtered", FILTERED_BUDGET);
    }

    private static void assertWithinBudget(final String scenario, final String path, final long budget) throws Exception
    {
        final URI uri = URI.create("http://127.0.0.1:" + gatewayPort + path);
        send(uri, WARMUP_REQUESTS);

        final long allocatedBefore = allocatedOnIoThreads();
        final long cpuBefore = cpuNanosOnIoThreads();
        send(uri, MEASURED_REQUESTS);
        final long cpuNanosPerRequest = (cpuNanosOnIoThreads() - cpuBefore) / MEASURED_REQUESTS;
        final long perRequest = (allocatedOnIoThreads() - allocatedBefore) / MEASURED_REQUESTS;

        logger.info("Per request, {}: {} us CPU, {} bytes allocated (budget {})", scenario, String.format("%.1f", cpuNanosPerRequest / 1000d), perRequest, budget);
        assertThat(perRequest)
                .as("bytes allocated per request on the gateway I/O threads, %s", scenario)
                .isLessThanOrEqualTo(budget);
    }

    private static void send(final URI uri, final int requests) throws Exception
    {
        final HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor())
        {
            final List<Future<?>> workers = new ArrayList<>();
            for (int w = 0; w < CONCURRENCY; w++)
            {
                workers.add(pool.submit(() ->
                {
                    for (int i = 0; i < requests / CONCURRENCY; i++)
                    {
                        final HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                        if (response.statusCode() != 200)
                        {
                            throw new IllegalStateException("Unexpected status " + response.statusCode() + " for " + uri);
                        }
                    }
                    return null;
                }));
            }
            for (final Future<?> worker : workers)
            {
                worker.get();
            }
        }
    }

    private static long allocatedOnIoThreads()
    {
        final com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long total = 0;
        for (final long allocated : threads.getThreadAllocatedBytes(ioThreadIds))
        {
            // -1 for a thread that has died; the I/O threads live as long as the gateway does.
            assertThat(allocated).as("allocation of a gateway I/O thread").isNotNegative();
            total += allocated;
        }
        return total;
    }

    private static long cpuNanosOnIoThreads()
    {
        final java.lang.management.ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long total = 0;
        for (final long id : ioThreadIds)
        {
            final long cpu = threads.getThreadCpuTime(id);
            assertThat(cpu).as("CPU time of a gateway I/O thread").isNotNegative();
            total += cpu;
        }
        return total;
    }

    private static Set<Long> ioThreadIds(final Set<Long> exclude)
    {
        final Set<Long> ids = new HashSet<>();
        for (final Thread thread : Thread.getAllStackTraces().keySet())
        {
            if (XNIO_IO_THREAD.matcher(thread.getName()).matches() && !exclude.contains(thread.threadId()))
            {
                ids.add(thread.threadId());
            }
        }
        return ids;
    }

    private static Path writeRoutes(final String backendUrl) throws IOException
    {
        // Mirrors benchmark/config/routes.yaml.tmpl, so these numbers and benchmark/run.sh
        // describe the same routes.
        final Path routes = dir.resolve("routes.yaml");
        Files.writeString(routes, """
                version: allocation
                routes:
                  - id: bench-passthrough
                    match:
                      - PathPrefix:
                          prefix: /bench
                    upstream:
                      targets:
                        - url: %1$s
                  - id: bench-journal
                    match:
                      - PathPrefix:
                          prefix: /journal
                    upstream:
                      targets:
                        - url: %1$s
                    journal:
                      request:
                        level: HEADERS
                      response:
                        level: HEADERS
                  - id: bench-filtered
                    match:
                      - PathPrefix:
                          prefix: /filtered
                    upstream:
                      targets:
                        - url: %1$s
                    filters:
                      - AddCorrelationId
                      - AddRequestHeader:
                          name: X-Gateway-Routed
                          value: "true"
                      - AddResponseHeader:
                          name: X-Powered-By
                          value: Ethlo R7
                """.formatted(backendUrl), StandardCharsets.UTF_8);
        return routes;
    }

    private static Path writeServer(final int dataPort, final int managementPort) throws IOException
    {
        final Path server = dir.resolve("server.yaml");
        Files.writeString(server, """
                server:
                  port: %d
                  host: 127.0.0.1
                management:
                  port: %d
                  host: 127.0.0.1
                storage:
                  work_dir: %s
                """.formatted(dataPort, managementPort, dir.resolve("journals").toAbsolutePath()), StandardCharsets.UTF_8);
        return server;
    }

    private static int freePort() throws IOException
    {
        try (ServerSocket socket = new ServerSocket(0))
        {
            return socket.getLocalPort();
        }
    }
}
