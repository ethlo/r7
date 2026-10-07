package com.ethlo.r7.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.ExchangeCompletionListener.BodyKind;
import com.ethlo.r7.journal.api.ExchangeCompletionListener.IncompleteReason;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link JsonLinesWriter} runs in the sidecar, on records a consumer already refused or gave
 * up on delivering, and its own failures have to be survivable in the same spirit as the
 * journal reader's: a transient failure must cost one record, not the writer, and a failure
 * must never look like success.
 */
class JsonLinesWriterTest
{
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * A write failure while a JSON object was only half written used to leave the shared
     * {@code JsonGenerator} with that object still open, so every record after the failure
     * came out malformed forever - one bad tick on a full disk would silently corrupt the
     * entire rest of the stream. Building each record into a private, infallible scratch
     * buffer before it ever touches the real output (see {@code JsonLinesWriter#flushRecord})
     * means a failure there can only ever cost the one record that failed.
     * <p>
     * The exchange written here is deliberately large: a generator bound straight to the
     * real destination only corrupts its own nesting state when its internal buffer fills
     * and flushes <em>mid-object</em> - a small record fits in one buffer and flushes only
     * at the end, where the damage does not yet show. Reproducing the failure this test
     * guards against needs enough content to force that mid-object flush.
     */
    @Test
    void aTransientOutputFailureCostsOnlyOneRecordNotTheWriter() throws IOException
    {
        final ByteArrayOutputStream real = new ByteArrayOutputStream();
        final FlakyOutputStream flaky = new FlakyOutputStream(real, 1);
        final JsonLinesWriter writer = new JsonLinesWriter(flaky);

        final JournalExchange exchange = bigExchange("req-1");

        assertThatThrownBy(() -> writer.onComplete(exchange))
                .as("the first, failing attempt must be reported rather than swallowed")
                .isInstanceOf(RuntimeException.class);
        assertThat(real.toByteArray()).as("nothing reaches the real sink from a failed attempt").isEmpty();

        // A retry, exactly as the reassembler performs for a refused record (FORMAT.md §6).
        writer.onComplete(exchange);

        final List<String> lines = linesOf(real);
        assertThat(lines).as("exactly one clean record, not the first attempt's wreckage plus the retry").hasSize(1);

        final JsonNode node = MAPPER.readTree(lines.get(0));
        assertThat(node.path("request_id").asString()).isEqualTo("req-1");

        // A second, independent record must still come out clean - proof the generator's own
        // nesting state was never touched by the earlier failure.
        writer.onComplete(bigExchange("req-2"));
        assertThat(linesOf(real)).hasSize(2);
    }

    /**
     * {@link PrintStream} - what {@code System.out} is, and this writer's documented default
     * destination - never throws from {@code write}/{@code flush}; a broken pipe just sets
     * an internal error flag. Left unchecked, every record after the pipe breaks would be
     * silently discarded while the tailer's checkpoint kept advancing as though they were
     * delivered.
     */
    @Test
    void aBrokenPrintStreamDestinationIsReportedRatherThanSwallowed()
    {
        final PrintStream broken = new PrintStream(new AlwaysFailingOutputStream(), true, StandardCharsets.UTF_8);
        final JsonLinesWriter writer = new JsonLinesWriter(broken);

        assertThatThrownBy(() -> writer.onComplete(completeExchange("req-1")))
                .as("a PrintStream that swallowed the write must still surface as a failure here")
                .isInstanceOf(RuntimeException.class);
    }

    /**
     * An exchange that ended but was not a complete record must not simply vanish: the
     * reassembler applied status, timing, traffic and checksums before calling this, so
     * there is a real record to write - flagged with why it is not a normal one.
     */
    @Test
    void onIncompleteEndIsCountedAndEmittedFlagged() throws IOException
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final JsonLinesWriter writer = new JsonLinesWriter(out);

        writer.onIncompleteEnd(completeExchange("req-partial"), IncompleteReason.NO_STATUS);

        assertThat(writer.getIncompleteEndCount()).isEqualTo(1);
        final JsonNode node = MAPPER.readTree(linesOf(out).get(0));
        assertThat(node.path("incomplete").asString()).isEqualTo("NO_STATUS");
        assertThat(node.path("request_id").asString()).isEqualTo("req-partial");
    }

    /**
     * An exchange whose EndExchange never arrived has no status, timing or traffic counters
     * - they are unknown, not zero (README.md §11.4). Writing them as zero would quietly lie
     * about response status and duration in the audit trail, so they must be absent, not
     * present-and-wrong.
     */
    @Test
    void onAbandonedIsCountedAndOmitsFieldsThatWereNeverSet() throws IOException
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final JsonLinesWriter writer = new JsonLinesWriter(out);

        final JournalExchange abandoned = new JournalExchange("req-abandoned");
        abandoned.setClientRequest("GET /never-finishes HTTP/1.1", JournalLevel.FULL,
                new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);

        writer.onAbandoned(abandoned, IncompleteReason.TIMED_OUT);

        assertThat(writer.getAbandonedCount()).isEqualTo(1);
        final JsonNode node = MAPPER.readTree(linesOf(out).get(0));
        assertThat(node.path("incomplete").asString()).isEqualTo("TIMED_OUT");
        assertThat(node.path("client_response").has("status"))
                .as("status was never set by an End event that never arrived - it must not read as 0")
                .isFalse();
        assertThat(node.has("start")).isFalse();
        assertThat(node.has("duration")).isFalse();
    }

    /**
     * When a WARC file holds the exchange, the line points at its records instead of carrying
     * the bodies a second time.
     */
    @Test
    void aLineForAnArchivedExchangePointsAtItsRecordsInsteadOfCarryingBodies() throws IOException
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final JsonLinesWriter writer = new JsonLinesWriter(out);
        final JournalExchange exchange = completeExchange("req-archived");
        exchange.appendRequestBody(ByteBuffer.wrap("payload".getBytes(StandardCharsets.UTF_8)));

        writer.writeComplete(exchange, new JsonLinesWriter.WarcPointer("r7-1-abc.warc.zst", 512, 300));
        writer.onComplete(exchange);

        final List<String> lines = linesOf(out);
        final JsonNode archived = MAPPER.readTree(lines.get(0));
        assertThat(archived.path("warc").path("file").asString()).isEqualTo("r7-1-abc.warc.zst");
        assertThat(archived.path("warc").path("offset").asLong()).isEqualTo(512);
        assertThat(archived.path("warc").path("length").asLong()).isEqualTo(300);
        assertThat(archived.path("client_request").has("body")).as("not even as null").isFalse();

        final JsonNode inline = MAPPER.readTree(lines.get(1));
        assertThat(inline.has("warc")).isFalse();
        assertThat(inline.path("client_request").path("body").isString()).isTrue();
    }

    /**
     * With bodies off, a line never carries a payload, and the sizes still show there was one.
     */
    @Test
    void withBodiesOffALineCarriesNoPayload() throws IOException
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final JsonLinesWriter writer = new JsonLinesWriter(out, false);
        final JournalExchange exchange = completeExchange("req-no-bodies");
        exchange.appendRequestBody(ByteBuffer.wrap("payload".getBytes(StandardCharsets.UTF_8)));
        exchange.setTraffic(10L, 7L, 0L, 0L);

        writer.onComplete(exchange);

        final JsonNode line = MAPPER.readTree(linesOf(out).get(0));
        assertThat(line.path("client_request").has("body")).as("not even as null").isFalse();
        assertThat(line.path("client_request").path("body_bytes").asLong()).isEqualTo(7);
    }

    /**
     * One object per leg, and nothing another field already says: no proxied flag (the
     * upstream objects are there or not), no error flag, no totals, no separate content type.
     */
    @Test
    void aRecordHasOneObjectPerLegAndNoDerivedFields() throws IOException
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final JsonLinesWriter writer = new JsonLinesWriter(out);

        final JournalExchange exchange = completeExchange("req-legs");
        exchange.setClientRequest("GET /items?page=2 HTTP/1.1", JournalLevel.FULL,
                new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        exchange.setUpstreamRequest("GET /v1/items?page=2 HTTP/1.1", JournalLevel.HEADERS, new MutableFastGatewayHeaders());
        exchange.setUpstreamResponse("HTTP/1.1 503 Service Unavailable", JournalLevel.HEADERS, new MutableFastGatewayHeaders());
        exchange.setClientResponse("HTTP/1.1 503 Service Unavailable", JournalLevel.HEADERS, new MutableFastGatewayHeaders());
        exchange.setTiming(1_000L, 9_000L, 2_000L, 5_000L, 8_000L);
        exchange.setStatus(503);
        exchange.setJournalChecksums(BodyChecksum.ofUnsigned32(7), BodyChecksum.NOT_RECORDED);

        writer.onComplete(exchange);

        final JsonNode node = MAPPER.readTree(linesOf(out).get(0));
        assertThat(node.propertyNames()).containsExactlyInAnyOrder(
                "request_id", "start", "end", "duration", "remote_address", "remote_address_source",
                "client_request", "upstream_request", "upstream_response", "client_response");

        final JsonNode clientRequest = node.path("client_request");
        assertThat(clientRequest.path("method").asString()).isEqualTo("GET");
        assertThat(clientRequest.path("path").asString()).isEqualTo("/items");
        assertThat(clientRequest.path("query").asString()).isEqualTo("page=2");
        assertThat(clientRequest.path("protocol").asString()).isEqualTo("HTTP/1.1");
        assertThat(clientRequest.path("level").asString()).isEqualTo("FULL");
        assertThat(clientRequest.path("checksum").asString()).isEqualTo("crc32c:00000007");
        assertThat(clientRequest.has("observed_checksum")).as("matches nothing it differs from").isFalse();

        assertThat(node.path("upstream_request").path("path").asString()).isEqualTo("/v1/items");
        assertThat(node.path("upstream_request").path("start").asString()).isEqualTo("1970-01-01T00:00:00.000002Z");
        assertThat(node.path("upstream_response").has("first_byte"))
                .as("the gateway never records it: -1 would come out as a 1969 timestamp").isFalse();
        assertThat(node.path("upstream_response").has("end")).as("the journal's upstream end is the exchange's end").isFalse();
        assertThat(node.path("upstream_response").path("status").asInt()).isEqualTo(503);
        assertThat(node.path("upstream_response").path("reason").asString()).isEqualTo("Service Unavailable");
        assertThat(node.path("client_response").path("status").asInt()).isEqualTo(503);
    }

    /**
     * The status is in the end event at every journal level, so a response whose start line
     * was not journaled still has one.
     */
    @Test
    void theStatusIsWrittenWhenTheStartLineWasNotJournaled() throws IOException
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final JsonLinesWriter writer = new JsonLinesWriter(out);

        final JournalExchange exchange = completeExchange("req-metadata");
        exchange.setStatus(404);
        writer.onComplete(exchange);

        final JsonNode response = MAPPER.readTree(linesOf(out).get(0)).path("client_response");
        assertThat(response.path("status").asInt()).isEqualTo(404);
        assertThat(response.path("level").asString()).isEqualTo("NONE");
    }

    /**
     * {@code String.format} follows the JVM's locale, and under one with a decimal comma it
     * wrote {@code "duration": 0,004290}, which is not JSON. A duration is always a number with
     * a decimal point.
     */
    @Test
    void durationsAreJsonNumbersWhateverTheLocale() throws IOException
    {
        final Locale saved = Locale.getDefault();
        try
        {
            Locale.setDefault(Locale.forLanguageTag("nb-NO"));
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final JournalExchange exchange = completeExchange("req-locale");
            exchange.setTiming(1_000L, 4_291_000L, -1L, -1L, -1L);
            new JsonLinesWriter(out).onComplete(exchange);

            final String line = linesOf(out).get(0);
            assertThat(line).contains("\"duration\":0.004290");
            assertThat(MAPPER.readTree(line).path("duration").isNumber()).isTrue();
        }
        finally
        {
            Locale.setDefault(saved);
        }
    }

    /**
     * A header sent once and a header sent twice have the same type, an array of strings, so a
     * typed consumer can give {@code headers} one column type.
     */
    @Test
    void headerAndAttributeValuesAreAlwaysArrays() throws IOException
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final JournalExchange exchange = completeExchange("req-arrays");
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.add("host", "api.example.com");
        headers.add("accept", "text/html");
        headers.add("accept", "application/json");
        exchange.setClientRequest("GET /items HTTP/1.1", JournalLevel.HEADERS, headers, InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        final FastGatewayAttributes attributes = new FastGatewayAttributes();
        attributes.add("gateway.auth.basic.user", "id:sha256:d4735e");
        exchange.setAttributes(attributes);

        new JsonLinesWriter(out).onComplete(exchange);

        final JsonNode node = MAPPER.readTree(linesOf(out).get(0));
        final JsonNode requestHeaders = node.path("client_request").path("headers");
        assertThat(requestHeaders.path("host").isArray()).isTrue();
        assertThat(requestHeaders.path("host").get(0).asString()).isEqualTo("api.example.com");
        assertThat(requestHeaders.path("accept")).hasSize(2);
        assertThat(node.path("attributes").path("gateway.auth.basic.user").isArray()).isTrue();
    }

    /**
     * The route and the upstream targets are fields of their own, not entries in
     * {@code attributes}, and the targets keep the order they were tried in.
     */
    @Test
    void theRouteAndTargetsAreFieldsOfTheirOwn() throws IOException
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final JournalExchange exchange = completeExchange("req-route");
        exchange.setUpstreamRequest("GET /v1/items HTTP/1.1", JournalLevel.HEADERS, new MutableFastGatewayHeaders());
        exchange.setUpstreamResponse("HTTP/1.1 200 OK", JournalLevel.HEADERS, new MutableFastGatewayHeaders());
        exchange.setTiming(1_000L, 9_000L, 2_000L, 5_000L, 8_000L);
        final FastGatewayAttributes attributes = new FastGatewayAttributes();
        attributes.add(JsonLinesWriter.ROUTE_ID_ATTRIBUTE, "items");
        attributes.add(JsonLinesWriter.UPSTREAM_TARGET_ATTRIBUTE, "http://a:8080");
        attributes.add(JsonLinesWriter.UPSTREAM_TARGET_ATTRIBUTE, "http://b:8080");
        attributes.add("gateway.fallback.id", "items-fallback");
        exchange.setAttributes(attributes);

        new JsonLinesWriter(out).onComplete(exchange);

        final JsonNode node = MAPPER.readTree(linesOf(out).get(0));
        assertThat(node.path("route_id").asString()).isEqualTo("items");
        assertThat(node.path("upstream_request").path("targets").get(0).asString()).isEqualTo("http://a:8080");
        assertThat(node.path("upstream_request").path("targets").get(1).asString()).isEqualTo("http://b:8080");
        assertThat(node.path("attributes").propertyNames()).containsExactly("gateway.fallback.id");
    }

    @Test
    void orphanedEndAndBodyAreCounted()
    {
        final JsonLinesWriter writer = new JsonLinesWriter(new ByteArrayOutputStream());

        writer.onOrphanedEnd("req-x");
        writer.onOrphanedEnd("req-y");
        writer.onOrphanedBody("req-z", BodyKind.REQUEST);

        assertThat(writer.getOrphanedEndCount()).isEqualTo(2);
        assertThat(writer.getOrphanedBodyCount()).isEqualTo(1);
    }

    private static JournalExchange completeExchange(final String reqId) throws IOException
    {
        final JournalExchange exchange = new JournalExchange(reqId);
        exchange.setClientRequest("GET /" + reqId + " HTTP/1.1", JournalLevel.FULL,
                new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        exchange.setTiming(1L, 2L, -1L, -1L, -1L);
        exchange.setTraffic(0L, 0L, 0L, 0L);
        exchange.setStatus(200);
        exchange.setAttributes(new FastGatewayAttributes());
        exchange.setJournalChecksums(BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);
        return exchange;
    }

    /**
     * As {@link #completeExchange(String)}, but padded with enough attribute content to
     * exceed a {@code JsonGenerator}'s internal output buffer - see the javadoc on
     * {@link #aTransientOutputFailureCostsOnlyOneRecordNotTheWriter()} for why that matters.
     */
    private static JournalExchange bigExchange(final String reqId) throws IOException
    {
        final JournalExchange exchange = completeExchange(reqId);
        final FastGatewayAttributes attrs = new FastGatewayAttributes();
        final String padding = "x".repeat(2000);
        for (int i = 0; i < 20; i++)
        {
            attrs.add("attr-" + i, padding);
        }
        exchange.setAttributes(attrs);
        return exchange;
    }

    private static List<String> linesOf(final ByteArrayOutputStream out)
    {
        final String content = out.toString(StandardCharsets.UTF_8);
        return content.isEmpty() ? List.of() : List.of(content.strip().split("\n"));
    }

    /**
     * Fails the first {@code failures} write attempts (any overload) and then behaves,
     * standing in for a disk that is briefly full.
     */
    private static final class FlakyOutputStream extends FilterOutputStream
    {
        private final AtomicInteger remaining;

        FlakyOutputStream(final OutputStream out, final int failures)
        {
            super(out);
            this.remaining = new AtomicInteger(failures);
        }

        @Override
        public void write(final int b) throws IOException
        {
            failIfNeeded();
            out.write(b);
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException
        {
            failIfNeeded();
            out.write(b, off, len);
        }

        private void failIfNeeded() throws IOException
        {
            if (remaining.getAndUpdate(n -> n > 0 ? n - 1 : n) > 0)
            {
                throw new IOException("disk full (test double)");
            }
        }
    }

    /**
     * Always throws - what {@link PrintStream} sits on top of and swallows.
     */
    private static final class AlwaysFailingOutputStream extends OutputStream
    {
        @Override
        public void write(final int b) throws IOException
        {
            throw new IOException("broken pipe (test double)");
        }
    }
}
