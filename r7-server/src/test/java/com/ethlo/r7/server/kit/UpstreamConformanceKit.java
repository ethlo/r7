package com.ethlo.r7.server.kit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * What a gateway must do with an upstream that breaks HTTP/1.1, checked on the wire: the other
 * direction from {@link GatewaySecurityKit}. Each case scripts a {@link ScriptedUpstream} to send
 * exactly the bytes under test and asserts what the client received - and, where framing is in
 * doubt, that the next request on the same route is unaffected. That second half is the point:
 * a gateway that reads one response's leftovers as the next response's head hands one client's
 * data to another, and nothing about the first response shows it.
 * <p>
 * A server module runs the kit by extending it and implementing {@link #startGateway}. Each case
 * has a route of its own, so a connection one case leaves pooled cannot disturb another.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class UpstreamConformanceKit
{
    private static final String OK = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok";

    private final Map<String, ScriptedUpstream> upstreams = new LinkedHashMap<>();
    private Path dir;
    private AutoCloseable gateway;
    private int gatewayPort;

    /**
     * Starts the server under test with these configuration files, listening on the data-plane
     * port they name, and returns a handle that stops it.
     */
    protected abstract AutoCloseable startGateway(Path routesYaml, Path serverYaml) throws Exception;

    /**
     * Extra top-level YAML for server.yaml, such as a proxy client selection.
     */
    protected String serverYamlExtra()
    {
        return "";
    }

    @BeforeAll
    void start() throws Exception
    {
        scripts();
        this.dir = Files.createTempDirectory("r7-upstream-kit-");
        this.gatewayPort = freePort();
        final StringBuilder routes = new StringBuilder("version: upstream-kit\nroutes:\n");
        for (final Map.Entry<String, ScriptedUpstream> e : this.upstreams.entrySet())
        {
            routes.append("""
                      - id: %s
                        match:
                          - PathPrefix:
                              prefix: /%s
                        upstream:
                          timeouts:
                            read: 5s
                          targets:
                            - url: %s
                    """.formatted(e.getKey(), e.getKey(), e.getValue().url()));
        }
        final Path routesFile = this.dir.resolve("routes.yaml");
        Files.writeString(routesFile, routes.toString(), StandardCharsets.UTF_8);
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
                %s
                """.formatted(this.gatewayPort, freePort(), this.dir.resolve("journals").toAbsolutePath(), serverYamlExtra()), StandardCharsets.UTF_8);
        this.gateway = startGateway(routesFile, server);
    }

    @AfterAll
    void stop() throws Exception
    {
        if (this.gateway != null)
        {
            this.gateway.close();
        }
        for (final ScriptedUpstream upstream : this.upstreams.values())
        {
            upstream.close();
        }
        if (this.dir != null)
        {
            try (var paths = Files.walk(this.dir))
            {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    // ============================================================================================
    // The scripts, one upstream and one route per case
    // ============================================================================================

    private void scripts() throws IOException
    {
        answerEvery("conflictinglengths", "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Length: 6\r\n\r\nhello!");
        answerEvery("repeatedlength", "HTTP/1.1 200 OK\r\nContent-Length: 5, 5\r\n\r\nhello");
        answerEvery("lengthandchunked", "HTTP/1.1 200 OK\r\nContent-Length: 100\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n");
        answerEvery("othercoding", "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n");
        answerEvery("folded", "HTTP/1.1 200 OK\r\nX-A: 1\r\n continued\r\nContent-Length: 2\r\n\r\nok");
        answerEvery("spacecolon", "HTTP/1.1 200 OK\r\nContent-Length : 2\r\n\r\nok");
        answerEvery("longheader", "HTTP/1.1 200 OK\r\nX-Long: " + "a".repeat(70_000) + "\r\nContent-Length: 2\r\n\r\nok");
        answerEvery("manyheaders", "HTTP/1.1 200 OK\r\n" + "X-H: 1\r\n".repeat(250) + "Content-Length: 2\r\n\r\nok");
        misbehaveOnFirst("shortbody", "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nhello", true);
        misbehaveOnFirst("signedchunk", "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n+5\r\nhello\r\n0\r\n\r\n", false);
        misbehaveOnFirst("longchunk", "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nhello\r\n0\r\n\r\n", false);
        answerEvery("nominated", "HTTP/1.1 200 OK\r\nConnection: X-Hop\r\nX-Hop: secret\r\nX-Keep: yes\r\nContent-Length: 2\r\n\r\nok");
        answerEvery("closetoken", "HTTP/1.1 200 OK\r\nConnection: keep-alive, close\r\nContent-Length: 2\r\n\r\nok");
        answerEvery("http10", "HTTP/1.0 200 OK\r\nContent-Length: 2\r\n\r\nok");
        answerEvery("interim", "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");
        add("untilclose", c ->
        {
            if (c.readRequest() != null)
            {
                c.write("HTTP/1.1 200 OK\r\n\r\nhello");
            }
        });
        add("early", c ->
        {
            if (c.readHeadOnly() != null)
            {
                c.write("HTTP/1.1 413 Payload Too Large\r\nContent-Length: 3\r\nConnection: close\r\n\r\nbig");
                c.lingeringClose();
            }
        });
        // The first connection answers one request, then takes the next and dies without
        // answering: to the gateway, a pooled connection that failed on reuse.
        for (final String name : List.of("staleget", "stalepost"))
        {
            add(name, c ->
            {
                if (c.index() == 1)
                {
                    if (c.readRequest() != null)
                    {
                        c.write(OK);
                        c.readRequest();
                    }
                    return;
                }
                while (c.readRequest() != null)
                {
                    c.write(OK);
                }
            });
        }
    }

    private void add(final String name, final ScriptedUpstream.Script script) throws IOException
    {
        this.upstreams.put(name, new ScriptedUpstream(script));
    }

    private void answerEvery(final String name, final String response) throws IOException
    {
        add(name, c ->
        {
            while (c.readRequest() != null)
            {
                c.write(response);
            }
        });
    }

    /**
     * The first connection gets the broken response, then either closes or is held open until
     * the gateway gives up on it; every later connection answers properly.
     */
    private void misbehaveOnFirst(final String name, final String response, final boolean closeAfter) throws IOException
    {
        add(name, c ->
        {
            if (c.index() == 1)
            {
                if (c.readRequest() != null)
                {
                    c.write(response);
                    if (!closeAfter)
                    {
                        c.awaitClose();
                    }
                }
                return;
            }
            while (c.readRequest() != null)
            {
                c.write(OK);
            }
        });
    }

    private ScriptedUpstream upstream(final String name)
    {
        return this.upstreams.get(name);
    }

    // ============================================================================================
    // Framing the gateway will not guess at: refused before anything reaches the client
    // ============================================================================================

    @Test
    void conflictingContentLengthsAreRefused() throws Exception
    {
        assertThat(get("conflictinglengths").status).isEqualTo(502);
    }

    @Test
    void aRepeatedEqualContentLengthIsOneLength() throws Exception
    {
        final Response response = get("repeatedlength");
        assertThat(response.status).isEqualTo(200);
        assertThat(response.bodyText()).isEqualTo("hello");
    }

    @Test
    void aTransferCodingOtherThanChunkedIsRefused() throws Exception
    {
        assertThat(get("othercoding").status).isEqualTo(502);
    }

    @Test
    void aFoldedHeaderIsRefused() throws Exception
    {
        assertThat(get("folded").status).isEqualTo(502);
    }

    @Test
    void whitespaceBeforeTheColonIsRefused() throws Exception
    {
        assertThat(get("spacecolon").status).isEqualTo(502);
    }

    @Test
    void anOverlongResponseHeaderIsRefused() throws Exception
    {
        assertThat(get("longheader").status).isEqualTo(502);
    }

    @Test
    void tooManyResponseHeadersAreRefused() throws Exception
    {
        assertThat(get("manyheaders").status).isEqualTo(502);
    }

    // ============================================================================================
    // Framing that fails after the response started: never complete, never carried over
    // ============================================================================================

    @Test
    void aShortContentLengthBodyNeverLooksComplete() throws Exception
    {
        assertIncompleteThenNextIsClean("shortbody");
    }

    @Test
    void aMalformedChunkSizeNeverLooksComplete() throws Exception
    {
        assertIncompleteThenNextIsClean("signedchunk");
    }

    @Test
    void aChunkLongerThanItsSizeNeverLooksComplete() throws Exception
    {
        assertIncompleteThenNextIsClean("longchunk");
    }

    private void assertIncompleteThenNextIsClean(final String name) throws Exception
    {
        final Response first = get(name);
        // An error of the gateway's own is fine (a server that buffered the head can still turn
        // it into one); what must not happen is the upstream's broken body arriving as a whole.
        assertThat(first.status >= 500 || !first.complete)
                .as("a response the upstream framed wrongly reached the client as complete: %s", first)
                .isTrue();
        final Response second = get(name);
        assertThat(second.status).isEqualTo(200);
        assertThat(second.bodyText()).isEqualTo("ok");
        assertThat(upstream(name).connections()).as("the broken connection was reused").isEqualTo(2);
    }

    // ============================================================================================
    // Framing and connection handling that is valid, and must be read right
    // ============================================================================================

    @Test
    void contentLengthWithChunkedIsReadAsChunkedAndTheConnectionIsNotReused() throws Exception
    {
        final Response first = get("lengthandchunked");
        final Response second = get("lengthandchunked");
        assertThat(first.bodyText()).isEqualTo("hello");
        assertThat(second.bodyText()).isEqualTo("hello");
        assertThat(upstream("lengthandchunked").connections()).isEqualTo(2);
    }

    @Test
    void headersTheUpstreamNominatesInConnectionStayAtTheGateway() throws Exception
    {
        final Response response = get("nominated");
        assertThat(response.status).isEqualTo(200);
        assertThat(response.header("X-Hop")).isNull();
        assertThat(response.header("X-Keep")).isEqualTo("yes");
    }

    @Test
    void closeAmongConnectionTokensIsHonoured() throws Exception
    {
        get("closetoken");
        get("closetoken");
        assertThat(upstream("closetoken").connections()).isEqualTo(2);
    }

    @Test
    void anHttp10ResponseWithoutKeepAliveIsNotReused() throws Exception
    {
        assertThat(get("http10").bodyText()).isEqualTo("ok");
        assertThat(get("http10").bodyText()).isEqualTo("ok");
        assertThat(upstream("http10").connections()).isEqualTo(2);
    }

    @Test
    void interimResponsesAreSkipped() throws Exception
    {
        final Response response = get("interim");
        assertThat(response.status).isEqualTo(200);
        assertThat(response.bodyText()).isEqualTo("ok");
    }

    @Test
    void aBodyWithoutFramingRunsToTheCloseAndTheConnectionIsNotReused() throws Exception
    {
        final Response response = get("untilclose");
        assertThat(response.status).isEqualTo(200);
        assertThat(response.bodyText()).isEqualTo("hello");
        get("untilclose");
        assertThat(upstream("untilclose").connections()).isEqualTo(2);
    }

    @Test
    void anEarlyResponseReachesTheClient() throws Exception
    {
        final byte[] body = new byte[2_000_000];
        final Response response = call("POST /early HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + body.length + "\r\n\r\n", body);
        assertThat(response.status).isEqualTo(413);
        assertThat(response.bodyText()).isEqualTo("big");
    }

    // ============================================================================================
    // A pooled connection that fails on reuse: retried only when that is harmless
    // ============================================================================================

    @Test
    void aGetThatFailsOnAReusedConnectionIsRetried() throws Exception
    {
        assertThat(get("staleget").status).isEqualTo(200);
        final Response retried = get("staleget");
        assertThat(retried.status).isEqualTo(200);
        assertThat(upstream("staleget").connections()).isEqualTo(2);
    }

    @Test
    void aPostIsNeverSentTwice() throws Exception
    {
        assertThat(get("stalepost").status).isEqualTo(200);
        final Response response = call("POST /stalepost HTTP/1.1\r\nHost: localhost\r\n\r\n", new byte[0]);
        assertThat(response.status).isEqualTo(502);
        final long posts = upstream("stalepost").requests().stream().filter(r -> r.method().equals("POST")).count();
        assertThat(posts).as("the upstream received the POST %d times", posts).isEqualTo(1);
    }

    // ============================================================================================
    // The client
    // ============================================================================================

    /**
     * A response as the client read it. {@code complete} is false when the connection ended
     * before the message did: short of its Content-Length, or without a chunked body's last chunk.
     */
    protected record Response(int status, List<String[]> headers, byte[] body, boolean complete)
    {
        String header(final String name)
        {
            for (final String[] h : this.headers)
            {
                if (h[0].equalsIgnoreCase(name))
                {
                    return h[1];
                }
            }
            return null;
        }

        String bodyText()
        {
            return new String(this.body, StandardCharsets.ISO_8859_1);
        }

        @Override
        public String toString()
        {
            return "status=" + this.status + " complete=" + this.complete + " body=" + bodyText();
        }
    }

    private Response get(final String name) throws IOException, InterruptedException
    {
        return call("GET /" + name + " HTTP/1.1\r\nHost: localhost\r\n\r\n", new byte[0]);
    }

    /**
     * Sends a request on a fresh connection - the body from another thread, so a gateway that
     * answers before reading it all cannot deadlock the test - and reads one response.
     */
    private Response call(final String head, final byte[] body) throws IOException, InterruptedException
    {
        try (Socket socket = new Socket("127.0.0.1", this.gatewayPort))
        {
            socket.setSoTimeout(10_000);
            final OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            final Thread writer = Thread.ofVirtual().start(() ->
            {
                try
                {
                    out.write(body);
                    out.flush();
                }
                catch (final IOException ignored)
                {
                    // The gateway stopped reading; the response says why.
                }
            });
            try
            {
                return read(socket.getInputStream(), head.startsWith("HEAD "));
            }
            finally
            {
                writer.join(10_000);
            }
        }
    }

    private static Response read(final InputStream in, final boolean head) throws IOException
    {
        final String statusLine = readLine(in);
        if (statusLine == null || statusLine.length() < 12)
        {
            return new Response(-1, List.of(), new byte[0], false);
        }
        final int status = Integer.parseInt(statusLine.substring(9, 12));
        final List<String[]> headers = new ArrayList<>();
        long length = -1;
        boolean chunked = false;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty())
        {
            final int colon = line.indexOf(':');
            final String name = line.substring(0, colon).trim();
            final String value = line.substring(colon + 1).trim();
            headers.add(new String[]{name, value});
            if (name.equalsIgnoreCase("Content-Length"))
            {
                length = Long.parseLong(value);
            }
            else if (name.equalsIgnoreCase("Transfer-Encoding") && value.toLowerCase(Locale.ROOT).contains("chunked"))
            {
                chunked = true;
            }
        }
        if (line == null)
        {
            return new Response(status, headers, new byte[0], false);
        }
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (head || status == 204 || status == 304)
        {
            return new Response(status, headers, new byte[0], true);
        }
        try
        {
            if (chunked)
            {
                while (true)
                {
                    final String sizeLine = readLine(in);
                    if (sizeLine == null)
                    {
                        return new Response(status, headers, body.toByteArray(), false);
                    }
                    final int size = Integer.parseInt(sizeLine.split(";")[0].trim(), 16);
                    if (size == 0)
                    {
                        readLine(in);
                        return new Response(status, headers, body.toByteArray(), true);
                    }
                    final byte[] chunk = in.readNBytes(size);
                    body.write(chunk);
                    if (chunk.length < size || readLine(in) == null)
                    {
                        return new Response(status, headers, body.toByteArray(), false);
                    }
                }
            }
            if (length >= 0)
            {
                final byte[] bytes = in.readNBytes((int) length);
                return new Response(status, headers, bytes, bytes.length == length);
            }
            return new Response(status, headers, in.readAllBytes(), true);
        }
        catch (final IOException e)
        {
            // Reset mid-body: whatever arrived, the message did not.
            return new Response(status, headers, body.toByteArray(), false);
        }
    }

    private static String readLine(final InputStream in) throws IOException
    {
        final StringBuilder sb = new StringBuilder();
        int b;
        try
        {
            while ((b = in.read()) != -1)
            {
                if (b == '\n')
                {
                    final int length = sb.length();
                    if (length > 0 && sb.charAt(length - 1) == '\r')
                    {
                        sb.setLength(length - 1);
                    }
                    return sb.toString();
                }
                sb.append((char) b);
            }
        }
        catch (final java.net.SocketException e)
        {
            return null;
        }
        return null;
    }

    private static int freePort() throws IOException
    {
        try (ServerSocket socket = new ServerSocket(0))
        {
            return socket.getLocalPort();
        }
    }
}
