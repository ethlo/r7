package com.ethlo.r7.tailer.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.json.JsonLdWriter;
import com.ethlo.r7.tailer.files.SealedFileWriter;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.warc.PayloadDedupIndex;
import com.ethlo.r7.warc.WarcExchangeWriter;
import com.ethlo.r7.warc.WarcFileWriter;
import com.github.luben.zstd.ZstdInputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ExchangeFanOutTest
{
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final long HOUR = 3_600_000L;

    @TempDir
    Path dir;

    /**
     * The JSON line carries the WARC location of its exchange, and the bytes there are exactly
     * that exchange's records.
     */
    @Test
    void theJsonLinePointsAtTheExchangesWarcRecords() throws IOException
    {
        final ByteArrayOutputStream json = new ByteArrayOutputStream();
        try (WarcFileWriter files = warcFiles())
        {
            final ExchangeFanOut fanOut = fanOut(files, WarcOutputConfig.Exchanges.ALL, json);
            fanOut.onComplete(exchange("req-1", "first body"));
            fanOut.onComplete(exchange("req-2", "second body"));
        }

        final JsonNode line = MAPPER.readTree(lines(json).get(1));
        assertThat(line.path("client_response").has("body")).as("the body is in the WARC file").isFalse();
        final JsonNode warc = line.path("warc");
        final byte[] file = Files.readAllBytes(dir.resolve(warc.path("file").asString()));
        final int offset = Math.toIntExact(warc.path("offset").asLong());
        final byte[] slice = Arrays.copyOfRange(file, offset, offset + Math.toIntExact(warc.path("length").asLong()));
        final String records = new String(new ZstdInputStream(new ByteArrayInputStream(slice)).readAllBytes(), StandardCharsets.UTF_8);
        assertThat(records).contains("req-2").contains("second body").doesNotContain("req-1");
    }

    /**
     * A JSON line that fails after the WARC records were written takes them back, so the retry
     * archives the exchange once, not twice. The dedup index learns nothing from the failed
     * attempt either: the retry stores the body rather than a revisit of records taken back.
     */
    @Test
    void aFailedJsonLineTakesBackTheExchangesWarcRecords() throws IOException
    {
        final ByteArrayOutputStream json = new ByteArrayOutputStream();
        try (WarcFileWriter files = warcFiles())
        {
            final ExchangeFanOut fanOut = fanOut(files, WarcOutputConfig.Exchanges.ALL, new FailOnce(json));
            final JournalExchange exchange = exchange("req-1", "the body");

            assertThatThrownBy(() -> fanOut.onComplete(exchange)).isInstanceOf(RuntimeException.class);
            fanOut.onComplete(exchange);
        }

        assertThat(lines(json)).hasSize(1);
        final String warc = warcText();
        assertThat(count(warc, "WARC-Type: response")).as("archived once").isEqualTo(1);
        assertThat(warc).doesNotContain("WARC-Type: revisit").contains("the body");
    }

    /**
     * With {@code exchanges: with_body}, only exchanges with a captured body get WARC records;
     * the others are in the JSON output alone, with no pointer.
     */
    @Test
    void withBodyArchivesOnlyExchangesThatHaveOne() throws IOException
    {
        final ByteArrayOutputStream json = new ByteArrayOutputStream();
        try (WarcFileWriter files = warcFiles())
        {
            final ExchangeFanOut fanOut = fanOut(files, WarcOutputConfig.Exchanges.WITH_BODY, json);
            fanOut.onComplete(exchange("req-empty", null));
            fanOut.onComplete(exchange("req-body", "a body"));
        }

        final List<String> lines = lines(json);
        assertThat(MAPPER.readTree(lines.get(0)).has("warc")).isFalse();
        assertThat(MAPPER.readTree(lines.get(1)).has("warc")).isTrue();
        assertThat(warcText()).contains("req-body").doesNotContain("req-empty");
    }

    @Test
    void warcAloneWritesNoJson() throws IOException
    {
        try (WarcFileWriter files = warcFiles())
        {
            new ExchangeFanOut(new WarcExchangeWriter(files, new PayloadDedupIndex(16)), WarcOutputConfig.Exchanges.ALL, null)
                    .onComplete(exchange("req-1", "a body"));
        }
        assertThat(warcText()).contains("req-1");
    }

    private WarcFileWriter warcFiles() throws IOException
    {
        return new WarcFileWriter(dir, "r7", SealedFileWriter.MIN_ROLLOVER_SIZE, HOUR, 3);
    }

    private static ExchangeFanOut fanOut(final WarcFileWriter files, final WarcOutputConfig.Exchanges exchanges, final OutputStream json)
    {
        return new ExchangeFanOut(new WarcExchangeWriter(files, new PayloadDedupIndex(16)), exchanges, new JsonLdWriter(json, false, true));
    }

    private static JournalExchange exchange(final String requestId, final String responseBody)
    {
        final JournalExchange exchange = new JournalExchange(requestId);
        exchange.setClientRequest("GET /" + requestId + " HTTP/1.1", JournalLevel.FULL,
                new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        exchange.setClientResponse("HTTP/1.1 200 OK", JournalLevel.FULL, new MutableFastGatewayHeaders());
        final byte[] body = responseBody != null ? responseBody.getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (body.length > 0)
        {
            exchange.appendResponseBody(ByteBuffer.wrap(body));
        }
        exchange.setTiming(1L, 2L, -1L, -1L, -1L);
        exchange.setTraffic(0L, 0L, 0L, body.length);
        exchange.setStatus(200);
        exchange.setAttributes(new FastGatewayAttributes());
        exchange.setJournalChecksums(BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);
        return exchange;
    }

    private String warcText() throws IOException
    {
        final Path sealed;
        try (Stream<Path> files = Files.list(dir))
        {
            sealed = files.filter(p -> p.getFileName().toString().endsWith(".warc.zst")).findFirst().orElseThrow();
        }
        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(Files.readAllBytes(sealed))))
        {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> lines(final ByteArrayOutputStream out)
    {
        final String content = out.toString(StandardCharsets.UTF_8);
        return content.isEmpty() ? List.of() : List.of(content.strip().split("\n"));
    }

    private static int count(final String text, final String needle)
    {
        return (int) Pattern.compile(Pattern.quote(needle)).matcher(text).results().count();
    }

    /**
     * Fails the first write, standing in for a disk that is briefly full.
     */
    private static final class FailOnce extends FilterOutputStream
    {
        private boolean failed;

        FailOnce(final OutputStream out)
        {
            super(out);
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException
        {
            if (!failed)
            {
                failed = true;
                throw new IOException("disk full (test double)");
            }
            out.write(b, off, len);
        }
    }
}
