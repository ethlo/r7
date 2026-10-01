package com.ethlo.r7.helidon;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.restassured.RestAssured;

/**
 * Request bodies of undeclared length (chunked) through the proxy, checked against the bytes the
 * upstream actually receives. WireMock is not used as the upstream on purpose: it accepts a
 * chunked body that ends early, which hides exactly the difference under test - whether the
 * gateway hands on a truncated body closed off with a terminating chunk, as if it were complete.
 */
public class RequestSizeLimitStreamingTest extends AbstractR7IntegrationTest
{
    private static final byte[] LAST_CHUNK = "\r\n0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private static ServerSocket rawUpstream;
    private static final BlockingQueue<Received> received = new LinkedBlockingQueue<>();

    /**
     * What one upstream connection carried, and whether it ended because the gateway closed it.
     */
    private record Received(byte[] bytes, boolean closedByGateway)
    {
        boolean endsWithLastChunk()
        {
            if (bytes.length < LAST_CHUNK.length)
            {
                return false;
            }
            for (int i = 0; i < LAST_CHUNK.length; i++)
            {
                if (bytes[bytes.length - LAST_CHUNK.length + i] != LAST_CHUNK[i])
                {
                    return false;
                }
            }
            return true;
        }
    }

    @BeforeAll
    public static void setupTopology() throws IOException
    {
        Assumptions.assumeTrue("in-process".equals(System.getProperty("r7.test.mode", "in-process")),
                "the raw upstream listens on the host loopback");

        rawUpstream = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        System.setProperty("RAW_UPSTREAM_PORT", String.valueOf(rawUpstream.getLocalPort()));
        Thread.ofVirtual().start(RequestSizeLimitStreamingTest::acceptLoop);

        startGateway("configs/request-size/request-size-routes.yaml");
    }

    @AfterAll
    public static void stopRawUpstream() throws IOException
    {
        if (rawUpstream != null)
        {
            rawUpstream.close();
        }
    }

    @BeforeEach
    public void clear()
    {
        received.clear();
    }

    /**
     * Records each connection until the gateway closes it or a complete chunked request has
     * arrived, which it answers so the proxied exchange can finish.
     */
    private static void acceptLoop()
    {
        while (!rawUpstream.isClosed())
        {
            try
            {
                final Socket socket = rawUpstream.accept();
                Thread.ofVirtual().start(() -> record(socket));
            }
            catch (final IOException closed)
            {
                return;
            }
        }
    }

    private static void record(final Socket socket)
    {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean closedByGateway = false;
        try (socket)
        {
            socket.setSoTimeout(3_000);
            final InputStream in = socket.getInputStream();
            final byte[] buffer = new byte[8192];
            while (true)
            {
                final int n = in.read(buffer);
                if (n == -1)
                {
                    closedByGateway = true;
                    break;
                }
                bytes.write(buffer, 0, n);
                if (new Received(bytes.toByteArray(), false).endsWithLastChunk())
                {
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                    break;
                }
            }
        }
        catch (final SocketException reset)
        {
            closedByGateway = true;
        }
        catch (final SocketTimeoutException stalled)
        {
            // Neither complete nor closed: recorded as such
        }
        catch (final IOException ignored)
        {
            // Recorded as far as it got
        }
        received.add(new Received(bytes.toByteArray(), closedByGateway));
    }

    @Test
    public void chunkedBodyOverTheLimitIsNeverDeliveredAsComplete() throws Exception
    {
        final String statusLine = send(chunkedRequest("/limited/upload", 5_000, true));

        Assertions.assertFalse(statusLine.startsWith("HTTP/1.1 2"), "an oversized chunked body was accepted: " + statusLine);
        final Received upstream = received.poll(5, TimeUnit.SECONDS);
        if (upstream != null)
        {
            Assertions.assertFalse(upstream.endsWithLastChunk(), "the upstream received a truncated body closed off as complete");
            Assertions.assertTrue(upstream.closedByGateway(), "the gateway left the upstream connection open mid-body");
        }
    }

    @Test
    public void chunkedBodyWithinTheLimitIsProxiedWhole() throws Exception
    {
        final String statusLine = send(chunkedRequest("/limited/upload", 500, true));

        Assertions.assertTrue(statusLine.startsWith("HTTP/1.1 200"), statusLine);
        final Received upstream = received.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(upstream);
        Assertions.assertTrue(upstream.endsWithLastChunk());
    }

    @Test
    public void declaredLengthOverTheLimitIsRefusedUpFront() throws Exception
    {
        final String body = "x".repeat(5_000);
        final String statusLine = send("POST /limited/upload HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: " + body.length() + "\r\n\r\n" + body);

        Assertions.assertTrue(statusLine.startsWith("HTTP/1.1 413"), statusLine);
        Assertions.assertNull(received.poll(1, TimeUnit.SECONDS), "a refused request reached the upstream");
    }

    /**
     * No size limit involved: a client that disconnects part-way through a chunked upload must
     * not have its partial body handed on as a finished request.
     */
    @Test
    public void clientDisconnectMidBodyIsNeverDeliveredAsComplete() throws Exception
    {
        try (final Socket socket = new Socket("localhost", RestAssured.port))
        {
            final OutputStream out = socket.getOutputStream();
            out.write(chunkedRequest("/unlimited/upload", 1_000, false).getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            // Give the gateway time to start streaming to the upstream before the client vanishes
            Thread.sleep(500);
        }

        final Received upstream = received.poll(8, TimeUnit.SECONDS);
        Assertions.assertNotNull(upstream, "expected the partial request to have reached the upstream");
        Assertions.assertFalse(upstream.endsWithLastChunk(), "the upstream received a truncated body closed off as complete");
        Assertions.assertTrue(upstream.closedByGateway(), "the gateway left the upstream connection open mid-body");
    }

    private static String chunkedRequest(final String path, final int bodySize, final boolean terminate)
    {
        final StringBuilder request = new StringBuilder("POST " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nTransfer-Encoding: chunked\r\n\r\n");
        for (int sent = 0; sent < bodySize; sent += 250)
        {
            final int size = Math.min(250, bodySize - sent);
            request.append(Integer.toHexString(size)).append("\r\n").append("x".repeat(size)).append("\r\n");
        }
        if (terminate)
        {
            request.append("0\r\n\r\n");
        }
        return request.toString();
    }

    /**
     * @return the status line, or an empty string if the gateway closed the connection without
     * answering, which is how a body that crosses the streaming limit is refused
     */
    private static String send(final String request) throws IOException
    {
        try (final Socket socket = new Socket("localhost", RestAssured.port))
        {
            socket.setSoTimeout(5_000);
            try
            {
                final OutputStream out = socket.getOutputStream();
                out.write(request.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }
            catch (final SocketException closedWhileSending)
            {
                return "";
            }
            final StringBuilder line = new StringBuilder();
            try
            {
                final InputStream in = socket.getInputStream();
                int b;
                while ((b = in.read()) != -1 && b != '\r')
                {
                    line.append((char) b);
                }
            }
            catch (final SocketException reset)
            {
                // Connection reset: nothing was answered
            }
            return line.toString();
        }
    }
}
