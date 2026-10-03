package com.ethlo.r7.warc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.tailer.files.SealedFileWriter;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.github.luben.zstd.ZstdInputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CdxjIndexTest
{
    private static final long HOUR = 3_600_000L;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path dir;

    @Test
    void indexesTheClientResponseOfEachExchange() throws IOException
    {
        try (WarcFileWriter files = writer(true))
        {
            final WarcExchangeWriter warc = new WarcExchangeWriter(files, new PayloadDedupIndex(100));
            warc.onComplete(exchange("req-1", "GET", "/v1/items?page=2", "{\"items\":[]}"));
            warc.onComplete(exchange("req-2", "DELETE", "/v1/items/7", "{\"deleted\":7}"));
        }

        final Path warcFile = only(".warc.zst");
        final List<String> lines = Files.readAllLines(only(".cdxj"));
        assertThat(lines).hasSize(2).isSorted();

        final String get = lineFor(lines, "req-1");
        assertThat(get).startsWith("com,example,api)/v1/items?page=2 ");
        assertThat(get.split(" ")[1]).matches("\\d{14}");
        final JsonNode getJson = json(get);
        assertThat(getJson.get("url").asString()).isEqualTo("http://api.example.com/v1/items?page=2");
        assertThat(getJson.get("status").asString()).isEqualTo("200");
        assertThat(getJson.get("mime").asString()).isEqualTo("application/json");
        assertThat(getJson.get("filename").asString()).isEqualTo(warcFile.getFileName().toString());
        assertThat(getJson.get("digest").asString()).isNotBlank();

        assertThat(lineFor(lines, "req-2")).startsWith("com,example,api)/v1/items/7?__wb_method=delete ");
    }

    @Test
    void offsetAndLengthLocateTheClientResponseRecord() throws IOException
    {
        try (WarcFileWriter files = writer(true))
        {
            new WarcExchangeWriter(files, new PayloadDedupIndex(100))
                    .onComplete(exchange("req-1", "GET", "/v1/items", "{\"items\":[]}"));
        }

        final JsonNode entry = json(Files.readAllLines(only(".cdxj")).getFirst());
        final byte[] warc = Files.readAllBytes(only(".warc.zst"));
        final int offset = Integer.parseInt(entry.get("offset").asString());
        final int length = Integer.parseInt(entry.get("length").asString());
        final String record = decompress(Arrays.copyOfRange(warc, offset, offset + length));

        assertThat(record)
                .startsWith("WARC/1.1\r\nWARC-Type: response\r\n")
                .contains("WARC-Target-URI: http://api.example.com/v1/items\r\n")
                .contains("{\"items\":[]}")
                .as("exactly one record").containsOnlyOnce("WARC/1.1");
    }

    @Test
    void aDeduplicatedResponseIsIndexedAsARevisit() throws IOException
    {
        try (WarcFileWriter files = writer(true))
        {
            final WarcExchangeWriter warc = new WarcExchangeWriter(files, new PayloadDedupIndex(100));
            warc.onComplete(exchange("req-1", "GET", "/logo", "same bytes"));
            warc.onComplete(exchange("req-2", "GET", "/logo", "same bytes"));
        }

        final List<String> lines = Files.readAllLines(only(".cdxj"));
        assertThat(lines).hasSize(2);
        assertThat(lines.stream().map(line -> json(line).get("mime").asString()))
                .containsExactlyInAnyOrder("text/plain", "warc/revisit");
    }

    @Test
    void noIndexUnlessEnabled() throws IOException
    {
        try (WarcFileWriter files = writer(false))
        {
            new WarcExchangeWriter(files, new PayloadDedupIndex(100))
                    .onComplete(exchange("req-1", "GET", "/v1/items", "{}"));
        }

        assertThat(namesEndingWith(".cdxj")).isEmpty();
    }

    /**
     * A crash can leave a WARC file open and an index half written; the next start removes the
     * partial index and indexes the WARC file as it seals it.
     */
    @Test
    void aFileLeftOpenByACrashIsIndexedWhenSealed() throws IOException
    {
        final WarcFileWriter crashed = writer(true);
        new WarcExchangeWriter(crashed, new PayloadDedupIndex(100))
                .onComplete(exchange("req-1", "GET", "/v1/items", "{}"));
        // No close(): the process died while sealing.
        final Path open = only(".warc.zst.open");
        final String baseName = open.getFileName().toString().replace(".warc.zst.open", "");
        Files.writeString(dir.resolve(baseName + ".cdxj.open"), "torn");

        writer(true).close();

        assertThat(namesEndingWith(".open")).isEmpty();
        final List<String> lines = Files.readAllLines(dir.resolve(baseName + ".cdxj"));
        assertThat(lines).hasSize(1);
        assertThat(json(lines.getFirst()).get("filename").asString()).isEqualTo(baseName + ".warc.zst");
    }

    private WarcFileWriter writer(final boolean cdxjIndex) throws IOException
    {
        return new WarcFileWriter(dir, "r7", SealedFileWriter.MIN_ROLLOVER_SIZE, HOUR, 3, cdxjIndex);
    }

    /**
     * A proxied exchange: the client called api.example.com, r7 forwarded to backend:8080
     * under another path, so the upstream response is a separate record with its own URI.
     */
    private static JournalExchange exchange(final String requestId, final String method, final String path, final String responseBody)
    {
        final byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        final JournalExchange exchange = new JournalExchange(requestId);
        exchange.setClientRequest(method + " " + path + " HTTP/1.1", JournalLevel.FULL,
                headers("Host", "api.example.com"), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        exchange.setUpstreamRequest(method + " /internal" + path + " HTTP/1.1", JournalLevel.FULL, headers("Host", "backend:8080"));
        final String contentType = responseBody.startsWith("{") ? "application/json" : "text/plain; charset=utf-8";
        exchange.setUpstreamResponse("HTTP/1.1 200 OK", JournalLevel.FULL, headers("Content-Type", contentType));
        exchange.setClientResponse("HTTP/1.1 200 OK", JournalLevel.FULL, headers("Content-Type", contentType));
        exchange.appendResponseBody(ByteBuffer.wrap(body));
        exchange.setTiming(1L, 4L, 2L, 3L, 3L);
        exchange.setTraffic(0L, 0L, 0L, body.length);
        exchange.setStatus(200);
        exchange.setAttributes(new FastGatewayAttributes());
        exchange.setJournalChecksums(BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);
        return exchange;
    }

    private static MutableFastGatewayHeaders headers(final String name, final String value)
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.add(name, value);
        return headers;
    }

    private String lineFor(final List<String> lines, final String requestId) throws IOException
    {
        final byte[] warc = Files.readAllBytes(only(".warc.zst"));
        for (final String line : lines)
        {
            final JsonNode entry = json(line);
            final int offset = Integer.parseInt(entry.get("offset").asString());
            final int length = Integer.parseInt(entry.get("length").asString());
            if (decompress(Arrays.copyOfRange(warc, offset, offset + length)).contains("WARC-R7-Request-Id: " + requestId + "\r\n"))
            {
                return line;
            }
        }
        throw new AssertionError("No index line for " + requestId + " in " + lines);
    }

    private static JsonNode json(final String line)
    {
        return JSON.readTree(line.substring(line.indexOf('{')));
    }

    private Path only(final String suffix) throws IOException
    {
        final List<Path> matching = namesEndingWith(suffix).stream().map(dir::resolve).toList();
        assertThat(matching).hasSize(1);
        return matching.getFirst();
    }

    private List<String> namesEndingWith(final String suffix) throws IOException
    {
        try (Stream<Path> files = Files.list(dir))
        {
            return files.map(p -> p.getFileName().toString()).filter(name -> name.endsWith(suffix)).toList();
        }
    }

    private static String decompress(final byte[] bytes) throws IOException
    {
        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(bytes)))
        {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
