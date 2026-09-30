package com.ethlo.r7.undertow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ethlo.r7.server.kit.ScriptedUpstream;

/**
 * Guards the one piece of Undertow code the r7 upstream client needs: the park/unpark bridge in
 * {@link ParkingChannelStreams}. A client that sends its body slowly and reads the response
 * slowly makes every read and write on the Undertow side hit "would block" many times. Through
 * Undertow's own blocking streams each of those waits opens a per-thread selector on the virtual
 * thread doing the relay (two file descriptors, released only by finalization); through the
 * bridge it parks. So no selector may open while such exchanges run - and every byte must still
 * arrive.
 */
class SlowPeerDescriptorTest
{
    private static final int BODY = 2 * 1024 * 1024;
    private static final int EXCHANGES = 8;

    private Path dir;
    private ScriptedUpstream upstream;
    private R7Main gateway;
    private int port;

    @BeforeEach
    void start() throws Exception
    {
        this.dir = Files.createTempDirectory("r7-slow-peer-");
        final byte[] response = pattern(BODY);
        this.upstream = new ScriptedUpstream(c ->
        {
            ScriptedUpstream.Request request;
            while ((request = c.readRequest()) != null)
            {
                assertThat(request.body()).hasSize(BODY);
                c.write("HTTP/1.1 200 OK\r\nContent-Length: " + BODY + "\r\n\r\n" + new String(response, StandardCharsets.ISO_8859_1));
            }
        });
        this.port = freePort();
        final Path routes = this.dir.resolve("routes.yaml");
        Files.writeString(routes, """
                version: slow-peer
                routes:
                  - id: slow
                    match:
                      - PathPrefix:
                          prefix: /
                    upstream:
                      targets:
                        - url: %s
                """.formatted(this.upstream.url()));
        final Path server = this.dir.resolve("server.yaml");
        Files.writeString(server, """
                server:
                  port: %d
                  host: 127.0.0.1
                management:
                  port: %d
                  host: 127.0.0.1
                storage:
                  work_dir: %s
                proxy:
                  client: r7
                """.formatted(this.port, freePort(), this.dir.resolve("journals").toAbsolutePath()));
        this.gateway = new R7Main(routes, server);
    }

    @AfterEach
    void stop() throws Exception
    {
        this.gateway.stop();
        this.upstream.close();
        try (Stream<Path> paths = Files.walk(this.dir))
        {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void slowClientsOpenNoSelectors() throws Exception
    {
        assumeTrue(Files.isDirectory(Path.of("/proc/self/fd")), "counts descriptors through /proc");

        // Warm up at full concurrency: that fills the upstream pool, the journal and the like.
        runBatch(EXCHANGES);
        final long before = epollDescriptors();

        // Sampled while the exchanges wait, not after: a per-thread selector is closed only by
        // finalization, and this test allocates fast enough for the GC to hide it by the end.
        final AtomicLong peak = new AtomicLong(before);
        final AtomicBoolean running = new AtomicBoolean(true);
        final Thread sampler = Thread.ofPlatform().daemon().start(() ->
        {
            while (running.get())
            {
                try
                {
                    peak.accumulateAndGet(epollDescriptors(), Math::max);
                    Thread.sleep(1);
                }
                catch (final IOException | InterruptedException e)
                {
                    return;
                }
            }
        });
        try
        {
            runBatch(EXCHANGES);
            runBatch(EXCHANGES);
        }
        finally
        {
            running.set(false);
            sampler.join();
        }

        assertThat(peak.get() - before)
                .as("epoll descriptors went from %d to a peak of %d during %d slow exchanges", before, peak.get(), 2 * EXCHANGES)
                .isLessThanOrEqualTo(1);
    }

    private void runBatch(final int n) throws Exception
    {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor())
        {
            final List<Future<byte[]>> results = new ArrayList<>();
            for (int i = 0; i < n; i++)
            {
                results.add(pool.submit((Callable<byte[]>) this::slowExchange));
            }
            final byte[] expected = pattern(BODY);
            for (final Future<byte[]> result : results)
            {
                assertThat(result.get(60, TimeUnit.SECONDS)).isEqualTo(expected);
            }
        }
    }

    /**
     * Sends a body in small pieces with pauses, then reads the response the same way, through a
     * small receive buffer so the gateway's writes back up.
     */
    private byte[] slowExchange() throws IOException, InterruptedException
    {
        try (Socket socket = new Socket())
        {
            socket.setReceiveBufferSize(4096);
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", this.port));
            socket.setSoTimeout(30_000);
            final OutputStream out = socket.getOutputStream();
            out.write(("POST /slow HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + BODY + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            final byte[] body = pattern(BODY);
            for (int off = 0; off < BODY; off += 64 * 1024)
            {
                out.write(body, off, Math.min(64 * 1024, BODY - off));
                out.flush();
                Thread.sleep(2);
            }
            final InputStream in = socket.getInputStream();
            skipHead(in);
            final byte[] received = new byte[BODY];
            int got = 0;
            while (got < BODY)
            {
                final int n = in.read(received, got, Math.min(16 * 1024, BODY - got));
                if (n == -1)
                {
                    break;
                }
                got += n;
                if ((got & 0x3ffff) < n)
                {
                    Thread.sleep(2);
                }
            }
            return got == BODY ? received : java.util.Arrays.copyOf(received, got);
        }
    }

    private static void skipHead(final InputStream in) throws IOException
    {
        int matched = 0;
        final byte[] end = {'\r', '\n', '\r', '\n'};
        int b;
        while (matched < 4 && (b = in.read()) != -1)
        {
            matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
        }
    }

    private static byte[] pattern(final int size)
    {
        final byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++)
        {
            bytes[i] = (byte) ('a' + i % 26);
        }
        return bytes;
    }

    /**
     * Selectors open in this process: each holds one epoll descriptor.
     */
    private static long epollDescriptors() throws IOException
    {
        try (Stream<Path> fds = Files.list(Path.of("/proc/self/fd")))
        {
            return fds.filter(fd ->
            {
                try
                {
                    return Files.readSymbolicLink(fd).toString().contains("eventpoll");
                }
                catch (final IOException e)
                {
                    return false;
                }
            }).count();
        }
    }

    private static int freePort() throws IOException
    {
        try (ServerSocket socket = new ServerSocket(0))
        {
            return socket.getLocalPort();
        }
    }
}
