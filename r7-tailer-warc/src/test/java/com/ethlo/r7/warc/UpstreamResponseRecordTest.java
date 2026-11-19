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
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.ExchangeCompletionListener.BodyKind;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.tailer.files.SealedFileWriter;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.github.luben.zstd.ZstdInputStream;

/**
 * What the response records claim about the body. Record 3 claims, through its payload digest,
 * that the upstream sent the body record 4 stores. That holds for a relayed response and is false
 * for a head r7 refused and answered itself. Record 4 says when it holds only the first
 * {@code truncate_body_at} bytes.
 */
class UpstreamResponseRecordTest
{
    @TempDir
    Path dir;

    @Test
    void aRelayedUpstreamResponseNamesTheBodyTheClientResponseStores() throws IOException
    {
        final List<String> responses = responseRecords(exchange("HTTP/1.1 200 OK", true, "HTTP/1.1 200 OK", "{\"ok\":true}"));

        assertThat(responses).hasSize(2);
        final String upstream = responses.get(0);
        final String client = responses.get(1);
        assertThat(upstream).contains("WARC-Truncated: unspecified\r\n").contains("WARC-Payload-Digest: ");
        assertThat(field(upstream, "WARC-Payload-Digest")).isEqualTo(field(client, "WARC-Payload-Digest"));
    }

    @Test
    void aRefusedUpstreamHeadClaimsNoBody() throws IOException
    {
        final List<String> responses = responseRecords(exchange("HTTP/1.1 101 Switching Protocols", false,
                "HTTP/1.1 502 Bad Gateway", "r7 refused the upgrade"));

        assertThat(responses).hasSize(2);
        final String upstream = responses.get(0);
        final String client = responses.get(1);
        assertThat(upstream).contains("HTTP/1.1 101 Switching Protocols")
                .contains("WARC-Truncated: unspecified\r\n")
                .doesNotContain("WARC-Payload-Digest");
        assertThat(client).contains("HTTP/1.1 502 Bad Gateway").contains("WARC-Payload-Digest: ").contains("r7 refused the upgrade");
    }

    @Test
    void aRefusedUpstreamHeadIsNotMarkedWithTheClientBodysChecksumMismatch() throws IOException
    {
        final JournalExchange exchange = exchange("HTTP/1.1 101 Switching Protocols", false, "HTTP/1.1 502 Bad Gateway", "r7 refused the upgrade");

        final List<String> responses = responseRecords(exchange, writer ->
                writer.onChecksumMismatch(exchange, BodyKind.RESPONSE, BodyChecksum.ofUnsigned32(1L), BodyChecksum.ofUnsigned32(2L)));

        assertThat(responses.get(1)).contains("WARC-R7-Checksum-Mismatch: true");
        assertThat(responses.get(0)).doesNotContain("WARC-R7-Checksum-Mismatch").doesNotContain("WARC-Payload-Digest");
    }

    /**
     * A capped body is stored as the prefix the journal kept, marked {@code WARC-Truncated:
     * length} with the digest of that prefix; a body exactly at the cap is an ordinary record.
     */
    @Test
    void aCappedBodyIsMarkedTruncatedByLengthWithThePrefixDigest() throws IOException
    {
        final JournalExchange capped = exchange("HTTP/1.1 200 OK", true, "HTTP/1.1 200 OK", "first bytes");
        capped.setTraffic(0L, 0L, 0L, 100_000L);
        capped.setBodyTruncated(false, true);

        final List<String> responses = responseRecords(capped);
        final String client = responses.get(1);
        assertThat(client).contains("WARC-Truncated: length\r\n").contains("first bytes");
        assertThat(field(client, "WARC-Payload-Digest")).isEqualTo(PayloadDigest.of(List.of(ByteBuffer.wrap("first bytes".getBytes(StandardCharsets.UTF_8)))));
        // The upstream record names the same prefix digest, so it says why too.
        assertThat(responses.get(0)).contains("WARC-Truncated: length\r\n");
        assertThat(field(responses.get(0), "WARC-Payload-Digest")).isEqualTo(field(client, "WARC-Payload-Digest"));
    }

    /**
     * With bodies left out of the archive, the record still says the body was capped: its digest
     * is of the prefix, not of the whole payload.
     */
    @Test
    void aCappedBodyLeftOutOfTheArchiveIsStillMarkedByLength() throws IOException
    {
        final JournalExchange capped = exchange("HTTP/1.1 200 OK", true, "HTTP/1.1 200 OK", "first bytes");
        capped.setTraffic(0L, 0L, 0L, 100_000L);
        capped.setBodyTruncated(false, true);

        final List<String> responses = responseRecords(capped, false, writer -> {});
        assertThat(responses).allSatisfy(r -> assertThat(r).contains("WARC-Truncated: length\r\n").doesNotContain("first bytes"));
    }

    @Test
    void anUncappedBodyIsNotMarkedTruncated() throws IOException
    {
        final String client = responseRecords(exchange("HTTP/1.1 200 OK", true, "HTTP/1.1 200 OK", "whole body")).get(1);
        assertThat(client).doesNotContain("WARC-Truncated").contains("whole body");
    }

    /**
     * A prefix is not the payload: a later exchange whose whole body happens to equal it must
     * not be written as a revisit of the capped record.
     */
    @Test
    void aCappedBodyIsNotADedupTarget() throws IOException
    {
        final JournalExchange capped = exchange("HTTP/1.1 200 OK", true, "HTTP/1.1 200 OK", "same");
        capped.setBodyTruncated(false, true);
        final JournalExchange whole = exchange("HTTP/1.1 200 OK", true, "HTTP/1.1 200 OK", "same");

        try (WarcFileWriter files = new WarcFileWriter(dir, "r7", SealedFileWriter.MIN_ROLLOVER_SIZE, 3_600_000L, 3, false))
        {
            final WarcExchangeWriter writer = new WarcExchangeWriter(files, new PayloadDedupIndex(100));
            writer.onComplete(capped);
            writer.onComplete(whole);
        }
        final String warc = decompress(Files.readAllBytes(only(".warc.zst")));
        assertThat(warc).doesNotContain("WARC-Type: revisit");
    }

    private List<String> responseRecords(final JournalExchange exchange) throws IOException
    {
        return responseRecords(exchange, writer -> {});
    }

    private List<String> responseRecords(final JournalExchange exchange, final Consumer<WarcExchangeWriter> beforeComplete) throws IOException
    {
        return responseRecords(exchange, true, beforeComplete);
    }

    private List<String> responseRecords(final JournalExchange exchange, final boolean bodies, final Consumer<WarcExchangeWriter> beforeComplete) throws IOException
    {
        try (WarcFileWriter files = new WarcFileWriter(dir, "r7", SealedFileWriter.MIN_ROLLOVER_SIZE, 3_600_000L, 3, false))
        {
            final WarcExchangeWriter writer = new WarcExchangeWriter(files, new PayloadDedupIndex(100), bodies);
            beforeComplete.accept(writer);
            writer.onComplete(exchange);
        }
        final String warc = decompress(Files.readAllBytes(only(".warc.zst")));
        return Arrays.stream(warc.split("WARC/1\\.1\r\n")).filter(r -> r.startsWith("WARC-Type: response\r\n")).toList();
    }

    private static JournalExchange exchange(final String upstreamLine, final boolean relayed, final String clientLine, final String responseBody)
    {
        final byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        final JournalExchange exchange = new JournalExchange("req-1");
        exchange.setClientRequest("GET /ws HTTP/1.1", JournalLevel.FULL, headers("Host", "api.example.com"), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        exchange.setUpstreamRequest("GET /ws HTTP/1.1", JournalLevel.FULL, headers("Host", "backend:8080"));
        exchange.setUpstreamResponse(upstreamLine, JournalLevel.FULL, headers("Upgrade", "websocket"), relayed);
        exchange.setClientResponse(clientLine, JournalLevel.FULL, headers("Content-Type", "text/plain"));
        exchange.appendResponseBody(ByteBuffer.wrap(body));
        exchange.setTiming(1L, 4L, 2L, 3L, 3L);
        exchange.setTraffic(0L, 0L, 0L, body.length);
        exchange.setStatus(Integer.parseInt(clientLine.substring(9, 12)));
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

    private static String field(final String record, final String name)
    {
        return record.lines().filter(l -> l.startsWith(name + ": ")).findFirst().orElseThrow().substring(name.length() + 2);
    }

    private Path only(final String suffix) throws IOException
    {
        try (Stream<Path> files = Files.list(dir))
        {
            final List<Path> matching = files.filter(p -> p.getFileName().toString().endsWith(suffix)).toList();
            assertThat(matching).hasSize(1);
            return matching.getFirst();
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
