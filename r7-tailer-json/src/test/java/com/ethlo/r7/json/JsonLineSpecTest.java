package com.ethlo.r7.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.ExchangeCompletionListener.IncompleteReason;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fields {@code docs/journaling.md} lists under "The JSON line" are the contract consumers
 * build on. Lines that between them carry every field must hold exactly the documented ones: a
 * field the writer adds without documenting it, or one the docs promise that the writer no
 * longer writes, fails here.
 */
class JsonLineSpecTest
{
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern ROW = Pattern.compile("^\\|\\s*`([a-z_.]+)`\\s*\\|([^|]*)\\|([^|]*)\\|");
    private static final Map<String, List<String>> LEG_GROUPS = Map.of(
            "all", List.of("client_request", "upstream_request", "upstream_response", "client_response"),
            "requests", List.of("client_request", "upstream_request"),
            "responses", List.of("upstream_response", "client_response"),
            "client legs", List.of("client_request", "client_response"),
            "`upstream_request`", List.of("upstream_request"),
            "`upstream_response`", List.of("upstream_response"));
    /**
     * Objects whose keys are data (header names, attribute names), not fields.
     */
    private static final Set<String> MAPS = Set.of("headers", "attributes");

    @Test
    void theWriterWritesExactlyTheDocumentedFields() throws IOException
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final JsonLinesWriter writer = new JsonLinesWriter(out, true);

        // Archived: the warc pointer, and no bodies.
        writer.writeComplete(proxied("req-archived"), new JsonLinesWriter.WarcPointer("r7-1-abc.warc.zst", 512, 300));
        // Not archived, ended without a status: bodies, a checksum mismatch and incomplete.
        writer.onIncompleteEnd(proxied("req-inline"), IncompleteReason.NO_STATUS);

        final Set<String> written = new TreeSet<>();
        for (final String line : out.toString(StandardCharsets.UTF_8).strip().split("\n"))
        {
            collect(MAPPER.readTree(line), "", written);
        }

        assertThat(written).isEqualTo(documentedFields());
    }

    private static JournalExchange proxied(final String requestId) throws IOException
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.add("host", "api.example.com");

        final JournalExchange exchange = new JournalExchange(requestId);
        exchange.setClientRequest("POST /items?page=2 HTTP/1.1", JournalLevel.FULL, headers, InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        exchange.setUpstreamRequest("POST /v1/items?page=2 HTTP/1.1", JournalLevel.FULL, headers);
        exchange.setUpstreamResponse("HTTP/1.1 201 Created", JournalLevel.FULL, headers);
        exchange.setClientResponse("HTTP/1.1 201 Created", JournalLevel.FULL, headers);
        exchange.appendRequestBody(ByteBuffer.wrap("request".getBytes(StandardCharsets.UTF_8)));
        exchange.appendResponseBody(ByteBuffer.wrap("response".getBytes(StandardCharsets.UTF_8)));
        exchange.setTiming(1_000L, 9_000L, 2_000L, 5_000L, 8_000L);
        exchange.setTraffic(40L, 7L, 30L, 8L);
        exchange.setStatus(201);
        // Neither matches the body read back, so observed_checksum is written too.
        exchange.setJournalChecksums(BodyChecksum.ofUnsigned32(7), BodyChecksum.ofUnsigned32(8));

        final FastGatewayAttributes attributes = new FastGatewayAttributes();
        attributes.add(JsonLinesWriter.ROUTE_ID_ATTRIBUTE, "items");
        attributes.add(JsonLinesWriter.UPSTREAM_TARGET_ATTRIBUTE, "http://backend:8080");
        attributes.add("gateway.fallback.id", "items-fallback");
        exchange.setAttributes(attributes);
        return exchange;
    }

    private static void collect(final JsonNode node, final String prefix, final Set<String> paths)
    {
        for (final String name : node.propertyNames())
        {
            final String path = prefix + name;
            paths.add(path);
            final JsonNode child = node.get(name);
            if (child.isObject() && !MAPS.contains(name))
            {
                collect(child, path + ".", paths);
            }
        }
    }

    /**
     * The two tables under "The JSON line": the exchange's fields as written, and the leg
     * fields expanded to each leg named in their "Legs" column.
     */
    private static Set<String> documentedFields() throws IOException
    {
        final List<String> lines = Files.readAllLines(repositoryRoot().resolve("docs/journaling.md"));
        final int start = lines.indexOf("### The JSON line");
        assertThat(start).as("docs/journaling.md has a section 'The JSON line'").isNotNegative();

        final Set<String> fields = new TreeSet<>();
        boolean legTable = false;
        for (int i = start + 1; i < lines.size() && !lines.get(i).startsWith("#"); i++)
        {
            final String line = lines.get(i);
            if (line.startsWith("| Field | Type | Legs |"))
            {
                legTable = true;
                continue;
            }
            final Matcher row = ROW.matcher(line);
            if (!row.find())
            {
                continue;
            }
            if (!legTable)
            {
                fields.add(row.group(1));
                continue;
            }
            final List<String> legs = LEG_GROUPS.get(row.group(3).strip());
            assertThat(legs).as("the Legs column of '%s' names a known group", row.group(1)).isNotNull();
            for (final String leg : legs)
            {
                fields.add(leg + "." + row.group(1));
            }
        }
        return fields;
    }

    private static Path repositoryRoot()
    {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent())
        {
            if (Files.isRegularFile(dir.resolve("mkdocs.yml")) && Files.isDirectory(dir.resolve("docs")))
            {
                return dir;
            }
        }
        throw new IllegalStateException("No repository root (a directory with mkdocs.yml and docs/) above " + Path.of("").toAbsolutePath());
    }
}
