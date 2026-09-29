package com.ethlo.r7.undertow;

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ethlo.r7.config.UnroutedDefinition;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalIntegrityListener;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.r7f.R7Tailer;

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
