package com.ethlo.r7.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * {@link JsonLdWriter} runs in the sidecar, on records a consumer already refused or gave
 * up on delivering, and its own failures have to be survivable in the same spirit as the
 * journal reader's: a transient failure must cost one record, not the writer, and a failure
 * must never look like success.
 */
class JsonLdWriterTest
{
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * A write failure while a JSON object was only half written used to leave the shared
     * {@code JsonGenerator} with that object still open, so every record after the failure
     * came out malformed forever - one bad tick on a full disk would silently corrupt the
     * entire rest of the stream. Building each record into a private, infallible scratch
     * buffer before it ever touches the real output (see {@code JsonLdWriter#flushRecord})
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
        final JsonLdWriter writer = new JsonLdWriter(flaky, false, true);

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
        assertThat(node.path("gateway_request_id").asString()).isEqualTo("req-1");

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
        final JsonLdWriter writer = new JsonLdWriter(broken, false, true);

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
        final JsonLdWriter writer = new JsonLdWriter(out, false, true);

        writer.onIncompleteEnd(completeExchange("req-partial"), IncompleteReason.NO_STATUS);

        assertThat(writer.getIncompleteEndCount()).isEqualTo(1);
        final JsonNode node = MAPPER.readTree(linesOf(out).get(0));
        assertThat(node.path("record_type").asString()).isEqualTo("incomplete_end");
        assertThat(node.path("incomplete_reason").asString()).isEqualTo("NO_STATUS");
        assertThat(node.path("gateway_request_id").asString()).isEqualTo("req-partial");
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
        // hideEmptyFields=false, so an omitted field shows up as an explicit null rather
        // than disappearing - the distinction this test needs to see.
        final JsonLdWriter writer = new JsonLdWriter(out, false, false);

        final JournalExchange abandoned = new JournalExchange("req-abandoned");
        abandoned.setClientRequest("GET /never-finishes HTTP/1.1", JournalLevel.FULL,
                new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);

        writer.onAbandoned(abandoned, IncompleteReason.TIMED_OUT);

        assertThat(writer.getAbandonedCount()).isEqualTo(1);
        final JsonNode node = MAPPER.readTree(linesOf(out).get(0));
        assertThat(node.path("record_type").asString()).isEqualTo("abandoned");
        assertThat(node.path("incomplete_reason").asString()).isEqualTo("TIMED_OUT");
        assertThat(node.path("status").isNull())
                .as("status was never set by an End event that never arrived - it must not read as 0")
                .isTrue();
        assertThat(node.path("start").isNull()).isTrue();
    }

    @Test
    void orphanedEndAndBodyAreCounted()
    {
        final JsonLdWriter writer = new JsonLdWriter(new ByteArrayOutputStream(), false, true);

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
