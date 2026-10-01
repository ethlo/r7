package com.ethlo.r7.helidon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What one proxied request allocates in the gateway, held to a budget.
 * <p>
 * Allocation is gated, but not because it is expensive: short-lived garbage on this path is a
 * TLAB bump and dies young, and GC does not register in a CPU profile of passthrough (see
 * design/server-spi.md, "Measured"). It is gated because it is deterministic enough for CI and it
 * moves when a layer of per-request objects is added. The headroom is about 10%, so it catches a
 * new wrapper graph, not a single stray lambda; perf stat and a profile catch the rest.
 * <p>
 * Níma runs every connection on a virtual thread, and the JDK reports no per-thread allocation or
 * CPU time for virtual threads. So the whole JVM is measured ({@code getTotalThreadAllocatedBytes}
 * counts virtual threads too), and the load generator and the backend are taken out: both are
 * raw sockets on platform threads that measure their own allocation as they go.
 * What remains is the gateway - its connection threads, the upstream client, the journal - plus
 * whatever the JVM's own threads allocate meanwhile, which over 30,000 requests is noise.
 * <p>
 * CPU time is not measured here. With nothing per thread to read, it would be the process's, and
 * at this test's light load that is dominated by the virtual-thread scheduler's carriers spinning
 * for work between short tasks - about 13 cores busy for 8 connections - not by requests. Compare
 * CPU between builds with {@code perf stat} instructions and cycles per request at load
 * (design/server-spi.md, "Order").
 * <p>
 * The budgets were reset when the test moved from Undertow to Níma: the whole JVM's allocation
 * per request is about 2.5 times what Undertow's I/O threads allocated (21 KB against 8 KB
 * passthrough). Where the difference goes has not been profiled.
 * <p>
 * The budgets are the measured values with headroom for JIT variation, not targets. When a change
 * lowers a number for good, lower its budget in the same PR so the gain cannot be lost again.
 */
class RequestPathCostTest
{
    private static final Logger logger = LoggerFactory.getLogger(RequestPathCostTest.class);
    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final byte[] CONTENT_LENGTH = "\ncontent-length:".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OK = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".getBytes(StandardCharsets.US_ASCII);

    // Enough for C2 to compile the path, and for escape analysis to have had its say, before
    // anything is counted: an interpreted request allocates far more than a compiled one.
    private static final int WARMUP_REQUESTS = 30_000;
    private static final int MEASURED_REQUESTS = 30_000;
    private static final int CONCURRENCY = 8;

    // Bytes per request; see the class comment for how they are set and maintained. Measured on
    // 2026-10-01 (JDK 25.0.4, Helidon 4.5.5): passthrough 20980-21220, journal HEADERS
    // 22036-22664, filtered 22295-22409. About 10% headroom: enough for JIT variation and a CI
    // runner, too little for a new per-request object graph to slip through unnoticed.
    private static final long PASSTHROUGH_BUDGET = 23_200;
    private static final long JOURNAL_HEADERS_BUDGET = 24_800;
    private static final long FILTERED_BUDGET = 24_600;

    @TempDir
    static Path dir;

    private static ServerSocket backend;
    private static R7Helidon gateway;
    private static int gatewayPort;

    // What the load generator and the backend allocate while measuring, to take out.
    private static final LongAdder harnessAllocated = new LongAdder();
    private static volatile boolean measuring;

    @BeforeAll
    static void start() throws Exception
    {
        backend = new ServerSocket(0, 64, java.net.InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().name("cost-backend-accept").start(RequestPathCostTest::accept);
        gatewayPort = freePort();
        gateway = new R7Helidon(writeRoutes("http://127.0.0.1:" + backend.getLocalPort()), writeServer(gatewayPort, freePort()));
    }

    @AfterAll
    static void stop() throws IOException
    {
        if (gateway != null)
        {
            gateway.stop();
        }
        if (backend != null)
        {
            backend.close();
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
        final byte[] request = ("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nAccept: */*\r\nUser-Agent: r7-cost\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        send(request, WARMUP_REQUESTS);

        harnessAllocated.reset();
        measuring = true;
        final long allocatedBefore = THREADS.getTotalThreadAllocatedBytes();
        send(request, MEASURED_REQUESTS);
        final long allocatedAfter = THREADS.getTotalThreadAllocatedBytes();
        measuring = false;

        final long perRequest = (allocatedAfter - allocatedBefore - harnessAllocated.sum()) / MEASURED_REQUESTS;
        logger.info("Per request, {}: {} bytes allocated (budget {})", scenario, perRequest, budget);
        assertThat(perRequest)
                .as("bytes allocated per request by the gateway, %s", scenario)
                .isLessThanOrEqualTo(budget);
    }

    /**
     * The load: {@link #CONCURRENCY} keep-alive connections on platform threads, each sending
     * its share of the requests one after another and reading each response to its end.
     */
    private static void send(final byte[] request, final int requests) throws Exception
    {
        final List<Thread> workers = new ArrayList<>();
        final List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int w = 0; w < CONCURRENCY; w++)
        {
            workers.add(Thread.ofPlatform().name("cost-client-" + w).start(() ->
            {
                final long allocatedStart = THREADS.getCurrentThreadAllocatedBytes();
                try (Socket socket = new Socket("127.0.0.1", gatewayPort))
                {
                    final OutputStream out = socket.getOutputStream();
                    final InputStream in = new BufferedInputStream(socket.getInputStream());
                    for (int i = 0; i < requests / CONCURRENCY; i++)
                    {
                        out.write(request);
                        out.flush();
                        readResponse(in);
                    }
                }
                catch (final Throwable e)
                {
                    failures.add(e);
                }
                if (measuring)
                {
                    harnessAllocated.add(THREADS.getCurrentThreadAllocatedBytes() - allocatedStart);
                }
            }));
        }
        for (final Thread worker : workers)
        {
            worker.join();
        }
        assertThat(failures).as("load generator failures").isEmpty();
    }

    /**
     * Reads one response head and its Content-Length body, without building strings.
     */
    private static void readResponse(final InputStream in) throws IOException
    {
        // The status code is bytes 9-11 of the status line.
        int status = 0;
        int position = 0;
        long contentLength = 0;
        int matched = 0;
        int nameMatched = 0;
        boolean inLength = false;
        while (matched < 4)
        {
            final int b = in.read();
            if (b == -1)
            {
                throw new IOException("The gateway closed the connection");
            }
            if (position >= 9 && position < 12)
            {
                status = status * 10 + (b - '0');
            }
            position++;
            matched = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3)) ? matched + 1 : (b == '\r' ? 1 : 0);
            if (inLength)
            {
                if (b >= '0' && b <= '9')
                {
                    contentLength = contentLength * 10 + (b - '0');
                }
                else if (b == '\r')
                {
                    inLength = false;
                }
            }
            nameMatched = Character.toLowerCase(b) == CONTENT_LENGTH[nameMatched] ? nameMatched + 1 : (b == '\n' ? 1 : 0);
            if (nameMatched == CONTENT_LENGTH.length)
            {
                inLength = true;
                nameMatched = 0;
            }
        }
        if (status != 200)
        {
            throw new IOException("Unexpected status " + status);
        }
        for (long i = 0; i < contentLength; i++)
        {
            if (in.read() == -1)
            {
                throw new IOException("The gateway closed the connection inside a body");
            }
        }
    }

    /**
     * The backend: a thread per upstream connection, answering every request head with a fixed
     * response. The gateway pools its connections, so these live through the run.
     */
    private static void accept()
    {
        while (!backend.isClosed())
        {
            try
            {
                final Socket socket = backend.accept();
                Thread.ofPlatform().daemon().name("cost-backend").start(() -> serve(socket));
            }
            catch (final IOException e)
            {
                return;
            }
        }
    }

    private static void serve(final Socket socket)
    {
        try (socket)
        {
            final InputStream in = new BufferedInputStream(socket.getInputStream());
            final OutputStream out = socket.getOutputStream();
            long allocated = THREADS.getCurrentThreadAllocatedBytes();
            while (true)
            {
                // Requests are GETs without a body: read to the blank line.
                int matched = 0;
                while (matched < 4)
                {
                    final int b = in.read();
                    if (b == -1)
                    {
                        return;
                    }
                    matched = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3)) ? matched + 1 : (b == '\r' ? 1 : 0);
                }
                out.write(OK);
                out.flush();
                final long allocatedNow = THREADS.getCurrentThreadAllocatedBytes();
                if (measuring)
                {
                    harnessAllocated.add(allocatedNow - allocated);
                }
                allocated = allocatedNow;
            }
        }
        catch (final IOException ignored)
        {
            // The gateway closed the connection.
        }
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
