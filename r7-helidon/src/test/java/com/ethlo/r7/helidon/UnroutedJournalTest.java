package com.ethlo.r7.helidon;

import static io.restassured.RestAssured.given;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ethlo.r7.config.UnroutedDefinition;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalIntegrityListener;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.r7f.R7Tailer;
import com.ethlo.r7.util.RedactUtil;

import io.restassured.RestAssured;

/**
 * Requests refused before any route is chosen are what scanners and probes send; with an
 * {@code unrouted} section they are journaled under {@code <unrouted>} with the reason, at the
 * section's levels, instead of leaving no trace.
 */
public class UnroutedJournalTest extends AbstractR7IntegrationTest
{
    // The in-process gateway journals to the default work_dir, relative to the module.
    private static final Path JOURNALS = Paths.get("journals");

    @BeforeAll
    public static void setupTopology()
    {
        // The journal is read from the host filesystem, which only the in-process gateway
        // writes to; the Docker modes journal inside their containers.
        Assumptions.assumeTrue(System.getProperty("r7.test.mode", "in-process").equals("in-process"), "reads the gateway's journal from the host filesystem");
        startGateway("configs/unrouted/routes.yaml");
    }

    @Test
    public void aRequestMatchingNoRouteIsJournaled() throws Exception
    {
        final String marker = "/probe-" + UUID.randomUUID();
        given().header("X-Probe", "yes").when().get(marker).then().statusCode(404);

        final JournalExchange entry = awaitEntry(marker);
        Assertions.assertEquals(404, entry.getStatus());
        Assertions.assertEquals(UnroutedDefinition.ROUTE_ID, entry.getAttributes().getFirst("gateway.route.id"));
        Assertions.assertEquals("no_route", entry.getAttributes().getFirst("gateway.unrouted.reason"));
        Assertions.assertEquals(JournalLevel.HEADERS, entry.getClientRequestLevel());
        Assertions.assertEquals(JournalLevel.METADATA, entry.getClientResponseLevel());
    }

    /**
     * Probes are where secrets in URLs turn up, and the request line is journaled at every
     * level: with no safe query parameters configured, no query value reaches the journal.
     */
    @Test
    public void queryValuesAreFingerprintedInTheJournal() throws Exception
    {
        final String marker = "/query-" + UUID.randomUUID();
        sendRaw("GET " + marker + "?api_key=s3cret&api_key=other&flag HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        final String startLine = awaitEntry(marker).getClientRequestStartLine();
        Assertions.assertEquals("GET " + marker + "?api_key=" + RedactUtil.fingerprint("s3cret") + "&api_key=" + RedactUtil.fingerprint("other")
                + "&" + RedactUtil.fingerprint("flag") + " HTTP/1.1", startLine);
    }

    @Test
    public void traceIsJournaledWithItsReason() throws Exception
    {
        final String marker = "/trace-" + UUID.randomUUID();
        given().when().request("TRACE", marker).then().statusCode(501);

        final JournalExchange entry = awaitEntry(marker);
        Assertions.assertEquals(501, entry.getStatus());
        Assertions.assertEquals("trace", entry.getAttributes().getFirst("gateway.unrouted.reason"));
    }

    @Test
    public void anAmbiguousPathIsJournaledWithItsReason() throws Exception
    {
        final String marker = "/amb-" + UUID.randomUUID();
        Assertions.assertTrue(sendRaw("GET /known/%2e%2e" + marker + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 400"));

        final JournalExchange entry = awaitEntry(marker);
        Assertions.assertEquals(400, entry.getStatus());
        Assertions.assertEquals("ambiguous_path", entry.getAttributes().getFirst("gateway.unrouted.reason"));
    }

    /**
     * Níma's parser refuses every request Transfer-Encoding but plain {@code chunked} itself -
     * 400 when the final coding is not chunked, 501 for a coding it does not implement - before
     * r7's own guard (TransferEncodingGuard) would. Such a request never becomes an exchange, so
     * there is nothing to journal, as with any request a server's parser refuses; what must hold
     * is that it is refused and its connection closed, so the body is never read as a request.
     */
    @Test
    public void aNonCanonicalTransferEncodingIsRefusedAndTheConnectionClosed() throws Exception
    {
        for (final String coding : new String[]{"chunked, identity", "gzip, chunked"})
        {
            final String response = sendRawFully("POST /known/te HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: " + coding + "\r\n\r\n0\r\n\r\n");
            Assertions.assertTrue(response.startsWith("HTTP/1.1 400") || response.startsWith("HTTP/1.1 501"), response);
            Assertions.assertTrue(response.toLowerCase().contains("connection: close"), "the connection was left open: " + response);
        }
    }

    @Test
    public void aRoutePatternExhaustingItsBudgetIsJournaledWithItsReason() throws Exception
    {
        final String marker = "/evil-" + UUID.randomUUID();
        given().header("X-Evil", "a".repeat(40) + "!").when().get(marker).then().statusCode(500);

        final JournalExchange entry = awaitEntry(marker);
        Assertions.assertEquals(500, entry.getStatus());
        Assertions.assertEquals("regex_budget", entry.getAttributes().getFirst("gateway.unrouted.reason"));
    }

    private static JournalExchange awaitEntry(final String marker) throws Exception
    {
        final Map<String, JournalExchange> found = new ConcurrentHashMap<>();
        final Path checkpoints = Files.createTempDirectory("r7-unrouted-test-checkpoints");
        final R7Tailer tailer = new R7Tailer(JOURNALS, checkpoints, exchange ->
        {
            final String startLine = exchange.getClientRequestStartLine();
            if (startLine != null && startLine.contains(marker))
            {
                found.put(marker, exchange);
            }
        }, JournalIntegrityListener.NOOP, ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1)));

        for (int attempt = 0; attempt < 50 && !found.containsKey(marker); attempt++)
        {
            tailer.runTick();
            if (!found.containsKey(marker))
            {
                Thread.sleep(100);
            }
        }
        Assertions.assertTrue(found.containsKey(marker), "no journal entry for " + marker);
        return found.get(marker);
    }

    /**
     * @return everything the gateway sent before closing; times out if it keeps the connection
     */
    private static String sendRawFully(final String request) throws IOException
    {
        try (final Socket socket = new Socket("localhost", RestAssured.port))
        {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static String sendRaw(final String request) throws IOException
    {
        try (final Socket socket = new Socket("localhost", RestAssured.port))
        {
            socket.setSoTimeout(5_000);
            final OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            final InputStream in = socket.getInputStream();
            final StringBuilder line = new StringBuilder();
            int b;
            while ((b = in.read()) != -1 && b != '\r')
            {
                line.append((char) b);
            }
            return line.toString();
        }
    }
}
