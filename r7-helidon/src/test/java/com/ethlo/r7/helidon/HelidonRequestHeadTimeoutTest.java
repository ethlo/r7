package com.ethlo.r7.helidon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.ethlo.r7.server.kit.ScriptedUpstream;

/**
 * http.request_parse_timeout on Helidon's data plane, which Helidon cannot enforce itself: a head
 * that is started and not finished in time ends the connection; a slow head that finishes in time
 * is served; and a keep-alive connection that is merely idle between requests is not mistaken
 * for a stalled head.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HelidonRequestHeadTimeoutTest
{
    private static final String OK = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok";

    private Path dir;
    private ScriptedUpstream upstream;
    private R7Helidon gateway;

    @BeforeAll
    void start() throws Exception
    {
        this.dir = Files.createTempDirectory("r7-helidon-head-");
        this.upstream = new ScriptedUpstream(c ->
        {
            while (c.readRequest() != null)
            {
                c.write(OK);
            }
        });
        final Path routes = this.dir.resolve("routes.yaml");
        Files.writeString(routes, """
                version: head
                routes:
                  - id: all
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
                http:
                  request_parse_timeout: 1s
                storage:
                  work_dir: %s
                """.formatted(freePort(), freePort(), this.dir.resolve("journals").toAbsolutePath()));
        this.gateway = new R7Helidon(routes, server);
    }

    @AfterAll
    void stop() throws IOException
    {
        if (this.gateway != null)
        {
            this.gateway.stop();
        }
        this.upstream.close();
        try (var paths = Files.walk(this.dir))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void aHeadStartedAndNotFinishedEndsTheConnection() throws Exception
    {
        try (Socket socket = new Socket("127.0.0.1", this.gateway.port()))
        {
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            socket.setSoTimeout(10_000);
            final long start = System.nanoTime();
            final InputStream in = socket.getInputStream();
            try
            {
                while (in.read() != -1)
                {
                    // drain until closed
                }
            }
            catch (final SocketTimeoutException e)
            {
                throw new AssertionError("A partial request head held its connection for 10s", e);
            }
            catch (final IOException closed)
            {
                // Reset: closed too.
            }
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000);
        }
    }

    @Test
    void aSlowHeadThatFinishesInTimeIsServed() throws Exception
    {
        try (Socket socket = new Socket("127.0.0.1", this.gateway.port()))
        {
            socket.setSoTimeout(10_000);
            final OutputStream out = socket.getOutputStream();
            for (final String part : new String[]{"GET / HTTP/1.1\r\n", "Host: localhost\r\n", "Connection: close\r\n", "\r\n"})
            {
                out.write(part.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread.sleep(150);
            }
            final String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertThat(response).startsWith("HTTP/1.1 200");
        }
    }

    @Test
    void anIdleKeepAliveConnectionIsNotAStalledHead() throws Exception
    {
        try (Socket socket = new Socket("127.0.0.1", this.gateway.port()))
        {
            socket.setSoTimeout(10_000);
            final OutputStream out = socket.getOutputStream();
            final InputStream in = socket.getInputStream();
            out.write("GET /one HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertThat(readResponse(in)).startsWith("HTTP/1.1 200");

            // Idle for longer than request_parse_timeout, with no head started.
            Thread.sleep(2_000);

            out.write("GET /two HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertThat(readResponse(in)).startsWith("HTTP/1.1 200");
        }
    }

    /**
     * One response with a Content-Length body, read exactly, leaving the connection usable.
     */
    private static String readResponse(final InputStream in) throws IOException
    {
        final StringBuilder head = new StringBuilder();
        int matched = 0;
        int b;
        while (matched < 4 && (b = in.read()) != -1)
        {
            head.append((char) b);
            matched = b == "\r\n\r\n".charAt(matched) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        final String text = head.toString();
        final int at = text.toLowerCase().indexOf("content-length:");
        final int length = at < 0 ? 0 : Integer.parseInt(text.substring(at + 15, text.indexOf('\r', at)).trim());
        return text + new String(in.readNBytes(length), StandardCharsets.ISO_8859_1);
    }

    private static int freePort() throws IOException
    {
        try (ServerSocket socket = new ServerSocket(0))
        {
            return socket.getLocalPort();
        }
    }
}
