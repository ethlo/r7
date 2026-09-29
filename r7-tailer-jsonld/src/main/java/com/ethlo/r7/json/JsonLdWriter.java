package com.ethlo.r7.json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.time.ClockSource;
import com.ethlo.r7.util.GatewayUtils;
import com.ethlo.r7.util.constants.HttpHeaders;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.time.ITU;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

public class JsonLdWriter implements ExchangeCompletionListener
{
    private static final Logger logger = LoggerFactory.getLogger(JsonLdWriter.class);
    private static final byte[] NEWLINE = "\n".getBytes(StandardCharsets.UTF_8);

    private final OutputStream out;
    private final boolean hideEmptyFields;

    /**
     * Scratch buffer the generator writes into, never {@link #out} directly.
     * <p>
     * A record is built here in full - object open to object close - before a single byte
     * reaches {@code out}. That is what keeps one transient output failure from wedging this
     * writer for good: if the generator wrote straight to {@code out} and a write failed
     * halfway through an object, the generator's own nesting state would be left with that
     * object still open, and every record after it would be malformed JSON forever. A
     * {@link ByteArrayOutputStream} cannot fail a write, so the generator bound to it can
     * never be left in a half-written state - only the final copy to {@code out} can fail,
     * and failing there costs this one record, not the writer.
     */
    private final ByteArrayOutputStream scratch = new ByteArrayOutputStream(1024);
    private final JsonGenerator generator;

    private final AtomicLong incompleteEndCount = new AtomicLong();
    private final AtomicLong abandonedCount = new AtomicLong();
    private final AtomicLong orphanedEndCount = new AtomicLong();
    private final AtomicLong orphanedBodyCount = new AtomicLong();

    public JsonLdWriter(OutputStream out, boolean prettyPrint)
    {
        this(out, prettyPrint, true);
    }

    /**
     * @param hideEmptyFields omit fields whose value is {@code null} or an empty map/collection
     *                        (unrecorded checksums, absent bodies, headers that were not
     *                        journaled, ...) instead of writing them out explicitly. Cuts line
     *                        size substantially on high-traffic routes where most of these
     *                        fields are empty on every request.
     */
    public JsonLdWriter(OutputStream out, boolean prettyPrint, boolean hideEmptyFields)
    {
        this.out = out;
        this.hideEmptyFields = hideEmptyFields;
        final JsonMapper mapper = JsonMapper.builder()
                .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
                .configure(JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS, false) // Ensure numbers stay as numbers
                .configure(SerializationFeature.WRITE_SINGLE_ELEM_ARRAYS_UNWRAPPED, true)
                .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.ALWAYS))
                .configure(SerializationFeature.INDENT_OUTPUT, prettyPrint)
                .build();

        // Bound to the scratch buffer for the writer's whole lifetime - see the field
        // javadoc for why this, and not out, is what the generator is allowed to touch.
        this.generator = mapper.createGenerator(scratch);
    }

    public void writePlainDouble(final JsonGenerator gen, final String fieldName, final double value) throws IOException
    {
        gen.writeName(fieldName);
        gen.writeRawValue(String.format("%.6f", value));
    }

    @Override
    public void onComplete(JournalExchange exchange)
    {
        try
        {
            writeExchangeObject(exchange, null, null);
            flushRecord();
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to write debug JSON", e);
        }
    }

    /**
     * An EndExchange arrived but the exchange did not qualify as a complete record. Status,
     * timing, traffic and the journaled checksums were all applied before this was called
     * (see {@code ExchangeReassembler#onEnd}), so this is written the same way a complete
     * record is, flagged with why it is not one - an operator reading the stream can still
     * use it, rather than lose it silently.
     */
    @Override
    public void onIncompleteEnd(final JournalExchange exchange, final IncompleteReason reason)
    {
        try
        {
            writeExchangeObject(exchange, "incomplete_end", reason);
            flushRecord();
        }
        catch (IOException e)
        {
            // Counted after the call, like every other counter here and in the reassembler
            // that calls this: a refusal is retried whole (FORMAT.md §6), and counting on
            // every attempt would report more incomplete records than were ever actually
            // written.
            throw new RuntimeException("Failed to write debug JSON", e);
        }

        final long total = incompleteEndCount.incrementAndGet();
        if (total == 1)
        {
            logger.warn("Exchange {} ended but was not a complete record ({}); emitted flagged as "
                            + "\"incomplete_end\". Further occurrences are counted, not logged.",
                    exchange.getRequestId(), reason);
        }
        else if (logger.isDebugEnabled())
        {
            logger.debug("Exchange {} ended incomplete ({}) (occurrence #{})", exchange.getRequestId(), reason, total);
        }
    }

    /**
     * No EndExchange ever arrived. Unlike {@link #onIncompleteEnd}, status, timing, traffic
     * and checksums are genuinely unknown - not zero - so they are omitted rather than
     * written as misleading zeros (README.md §11.4).
     * <p>
     * Not retried on failure: an abandoned exchange has no journal entry left to rewind to
     * (see {@code ExchangeReassembler#evictIncomplete}), so an {@link IOException} here is
     * logged and the record dropped rather than thrown.
     */
    @Override
    public void onAbandoned(final JournalExchange exchange, final IncompleteReason reason)
    {
        try
        {
            writeExchangeObject(exchange, "abandoned", reason);
            flushRecord();
        }
        catch (final IOException | RuntimeException e)
        {
            logger.error("Failed to write abandoned exchange {} as JSON; the record is lost, there is nothing "
                    + "left to retry it against.", exchange.getRequestId(), e);
            return;
        }

        final long total = abandonedCount.incrementAndGet();
        if (total == 1)
        {
            logger.warn("Exchange {} never received an EndExchange ({}); emitted flagged as \"abandoned\" "
                            + "with its known fields only. Further occurrences are counted, not logged.",
                    exchange.getRequestId(), reason);
        }
        else if (logger.isDebugEnabled())
        {
            logger.debug("Exchange {} abandoned ({}) (occurrence #{})", exchange.getRequestId(), reason, total);
        }
    }

    @Override
    public void onOrphanedEnd(final String requestId)
    {
        final long total = orphanedEndCount.incrementAndGet();
        if (total == 1)
        {
            logger.warn("Received an EndExchange for '{}' with nothing in flight; expected while a tailer "
                            + "is catching up, suspicious otherwise. Further occurrences are counted, not logged.",
                    requestId);
        }
        else if (logger.isDebugEnabled())
        {
            logger.debug("Orphaned end for '{}' (occurrence #{})", requestId, total);
        }
    }

    @Override
    public void onOrphanedBody(final String requestId, final BodyKind kind)
    {
        final long total = orphanedBodyCount.incrementAndGet();
        if (total == 1)
        {
            logger.warn("Received a {} body chunk for '{}' with no preceding start event. Further occurrences "
                    + "are counted, not logged.", kind, requestId);
        }
        else if (logger.isDebugEnabled())
        {
            logger.debug("Orphaned {} body for '{}' (occurrence #{})", kind, requestId, total);
        }
    }

    public long getIncompleteEndCount()
    {
        return incompleteEndCount.get();
    }

    public long getAbandonedCount()
    {
        return abandonedCount.get();
    }

    public long getOrphanedEndCount()
    {
        return orphanedEndCount.get();
    }

    public long getOrphanedBodyCount()
    {
        return orphanedBodyCount.get();
    }

    /**
     * Flushes whatever {@link #generator} wrote into {@link #scratch} out to the real
     * destination, and resets the scratch buffer for the next record.
     * <p>
     * A {@link PrintStream} - {@code System.out}, the default destination - never throws
     * {@link IOException} from {@code write}/{@code flush}; it swallows the failure and sets
     * an internal error flag instead. Left unchecked, a broken stdout pipe (the downstream
     * log collector died, or the container is being torn down) would have every record
     * silently discarded while the tailer's own checkpoint still advances, reporting
     * everything as delivered. Checking {@link PrintStream#checkError()} after every write
     * turns that into the same retried failure an {@link IOException} would produce for any
     * other {@link OutputStream}.
     */
    private void flushRecord() throws IOException
    {
        generator.flush();
        final byte[] record = scratch.toByteArray();
        scratch.reset();

        out.write(record);
        out.write(NEWLINE);
        out.flush();

        if (out instanceof final PrintStream printStream && printStream.checkError())
        {
            throw new IOException("Output stream reported a write error (e.g. a broken pipe); "
                    + "the record was not confirmed delivered");
        }
    }

    /**
     * Writes one exchange as a JSON object into {@link #generator} (and so into
     * {@link #scratch}), without touching {@link #out}.
     *
     * @param recordType null for a complete record, otherwise {@code "incomplete_end"} or
     *                   {@code "abandoned"}
     * @param reason     null for a complete record, otherwise why it is not one
     */
    private void writeExchangeObject(final JournalExchange exchange, final String recordType, final IncompleteReason reason) throws IOException
    {
        // Whether the EndExchange event was ever seen - and so whether status, timing,
        // traffic and checksums were ever applied to this exchange at all (README.md
        // §11.4). Treating an abandoned exchange's unset fields as zero would quietly lie
        // about response status and duration in the audit trail.
        final boolean hasEndEvent = reason == null || reason.hasEndEvent();

        generator.writeStartObject();

        // --- Metadata ---
        generator.writeStringProperty("gateway_request_id", exchange.getRequestId());
        if (recordType != null)
        {
            generator.writeStringProperty("record_type", recordType);
            generator.writeStringProperty("incomplete_reason", reason.name());
        }
        writeString("remote_address", Optional.ofNullable(exchange.remoteAddress()).map(InetAddress::getHostAddress).orElse(null));
        writeString("remote_address_source", Optional.ofNullable(exchange.getRemoteAddressSource()).map(Enum::toString).orElse(null));

        // wasProxied() reads proxyStartTs, which is only meaningful once the End event has
        // set it; for an abandoned exchange it is still its zero default; falling back to
        // whether an upstream leg was ever recorded avoids reporting "proxied" for a
        // request whose proxy timing simply never arrived.
        final boolean proxied = hasEndEvent
                ? exchange.wasProxied()
                : (exchange.getUpstreamRequestStartLine() != null || exchange.getUpstreamResponseStartLine() != null);

        if (hasEndEvent)
        {
            generator.writeStringProperty("start", ITU.formatUtcMicro(ClockSource.convertToUtc(exchange.getClientStartTs())));
            writePlainDouble(generator, "duration", exchange.getDurationNanos() / 1_000_000_000D);
            generator.writeStringProperty("end", ITU.formatUtcMicro(ClockSource.convertToUtc(exchange.getClientEndTs())));

            generator.writeBooleanProperty("was_proxied", proxied);

            if (proxied)
            {
                generator.writeStringProperty("proxy_start", ITU.formatUtcMicro(ClockSource.convertToUtc(exchange.getProxyStartTs())));
                generator.writeStringProperty("proxy_first_byte", ITU.formatUtcMicro(ClockSource.convertToUtc(exchange.getProxyFirstByteReceivedTs())));
                generator.writeStringProperty("proxy_end", ITU.formatUtcMicro(ClockSource.convertToUtc(exchange.getProxyEndTs())));
                writePlainDouble(generator, "proxy_duration", exchange.getProxyDurationNanos() / 1_000_000_000D);
            }

            // --- Metrics ---
            final int status = exchange.getStatus();
            generator.writeNumberProperty("status", status);
            generator.writeBooleanProperty("is_error", status >= HttpStatuses.BAD_REQUEST);
            writeNumber("request_header_bytes", exchange.getRequestHeaderBytes());
            writeNumber("request_body_bytes", exchange.getRequestBodyBytes());
            writeNumber("request_total_bytes", exchange.getRequestTotalBytes());
            writeNumber("response_header_bytes", exchange.getResponseHeaderBytes());
            writeNumber("response_body_bytes", exchange.getResponseBodyBytes());
            writeNumber("response_total_bytes", exchange.getResponseTotalBytes());

            // --- Checksums ---
            // "Journaled" is what the gateway recorded to disk; "observed" is what this reader
            // actually saw. A mismatch between the two means the segment was damaged in transit.
            // Named "checksum", not "crc32": the algorithm is CRC32C (see BodyChecksum), a
            // different polynomial than CRC-32, and the field name must not claim otherwise.
            writeChecksum("journaled_request_checksum", exchange.getJournaledRequestChecksum());
            writeChecksum("journaled_response_checksum", exchange.getJournaledResponseChecksum());
            writeChecksum("observed_request_checksum", exchange.getObservedRequestChecksum());
            writeChecksum("observed_response_checksum", exchange.getObservedResponseChecksum());
        }
        else
        {
            // No End event: timing, status and traffic counters are unknown, not zero
            // (README.md §11.4) - explicit nulls, subject to the same hideEmptyFields choice
            // as every other absent field, rather than a value that never applied. The
            // journaled checksums are equally unknown (they only ever come from the End
            // event, and writeChecksum already renders NOT_RECORDED as null); the observed
            // ones are still worth writing, because they reflect whatever body fragments
            // were actually seen before the exchange was abandoned - real evidence, not a
            // guess, for a truncated upload or download.
            generator.writeBooleanProperty("was_proxied", proxied);
            writeNull("start");
            writeNull("duration");
            writeNull("end");
            writeNull("status");
            writeNull("is_error");
            writeNull("request_header_bytes");
            writeNull("request_body_bytes");
            writeNull("request_total_bytes");
            writeNull("response_header_bytes");
            writeNull("response_body_bytes");
            writeNull("response_total_bytes");
            writeChecksum("journaled_request_checksum", exchange.getJournaledRequestChecksum());
            writeChecksum("journaled_response_checksum", exchange.getJournaledResponseChecksum());
            writeChecksum("observed_request_checksum", exchange.getObservedRequestChecksum());
            writeChecksum("observed_response_checksum", exchange.getObservedResponseChecksum());
        }

        // --- Client Object ---
        generator.writeName("client");
        writeExchangeNode(
                exchange.getClientRequestLevel(),
                exchange.getClientRequestStartLine(),
                exchange.getClientRequestHeaders(),
                exchange.getClientResponseLevel(),
                exchange.getClientResponseStartLine(),
                exchange.getClientResponseHeaders()
        );

        // --- Upstream Object ---
        if (proxied)
        {
            generator.writeName("upstream");
            writeExchangeNode(
                    exchange.getUpstreamRequestLevel(),
                    exchange.getUpstreamRequestStartLine(),
                    exchange.getUpstreamRequestHeaders(),
                    exchange.getUpstreamResponseLevel(),
                    exchange.getUpstreamResponseStartLine(),
                    exchange.getUpstreamResponseHeaders()
            );
        }

        // --- Payload Debugging ---
        writeBody("request_body", exchange.getRequestBodyFragments());
        writeBody("response_body", exchange.getResponseBodyFragments());

        // --- Context ---
        writeMap("attributes", GatewayUtils.toMap(exchange.getAttributes()));

        generator.writeEndObject();
    }

    private void writeExchangeNode(
            final JournalLevel reqLevel, final String reqLine, final GatewayHeaders reqHeaders,
            final JournalLevel resLevel, final String resLine, final GatewayHeaders resHeaders) throws IOException
    {
        generator.writeStartObject();

        // Request half
        generator.writeStringProperty("request_journal_level", reqLevel != null ? reqLevel.name() : "NONE");

        if (reqLine != null)
        {
            writeRequestLine(generator, reqLine);
        }
        else
        {
            // Maintain strict JSON schema consistency for columnar databases, unless the
            // caller has opted into hiding empty fields
            writeNull("method");
            writeNull("path");
            writeNull("query_string");
            writeNull("request_protocol");
        }

        writeMap("request_headers", GatewayUtils.toMap(reqHeaders));

        // Response half
        generator.writeStringProperty("response_journal_level", resLevel != null ? resLevel.name() : "NONE");
        writeMap("response_headers", GatewayUtils.toMap(resHeaders));
        writeString("content_type", getHeader(resHeaders, HttpHeaders.CONTENT_TYPE));

        writeResponseLine(generator, resLine);

        generator.writeEndObject();
    }

    private void writeResponseLine(final JsonGenerator generator, final String resLine)
    {
        // Expected format: "{PROTOCOL} {CODE} {REASON}"
        // Example: "HTTP/1.1 503 Service Unavailable"
        // Only "protocol" needs a request_/response_ prefix here: it is the one field that
        // exists on both start lines. "status_code" and "reason" are response-only concepts
        // and get no prefix, same as "method"/"path"/"query_string" on the request side.
        if (resLine == null)
        {
            writeNull("response_protocol");
            writeNull("status_code");
            writeNull("reason");
            return;
        }

        final int firstSpace = resLine.indexOf(' ');

        if (firstSpace != -1)
        {
            // 1. Protocol (e.g., "HTTP/1.1")
            generator.writeStringProperty("response_protocol", resLine.substring(0, firstSpace));

            // Find the space separating the Code and the Reason phrase
            final int secondSpace = resLine.indexOf(' ', firstSpace + 1);

            if (secondSpace != -1)
            {
                // 2. Status Code
                writeStatusCode(resLine.substring(firstSpace + 1, secondSpace));

                // 3. Reason Phrase (everything after the second space)
                generator.writeStringProperty("reason", resLine.substring(secondSpace + 1));
            }
            else
            {
                // Fallback if there is no reason phrase
                writeStatusCode(resLine.substring(firstSpace + 1));
                writeNull("reason");
            }
        }
        else
        {
            // Graceful fallback for completely malformed lines
            writeNull("response_protocol");
            writeStatusCode(resLine);
            writeNull("reason");
        }
    }

    /**
     * Writes {@code status_code} as a number, matching the top-level {@code status} field's
     * type. A start line that fails to parse is malformed, not merely absent, so it is
     * reported as null rather than silently smuggled through as a string.
     */
    private void writeStatusCode(final String code)
    {
        try
        {
            generator.writeNumberProperty("status_code", Integer.parseInt(code));
        }
        catch (final NumberFormatException e)
        {
            writeNull("status_code");
        }
    }

    private void writeRequestLine(final JsonGenerator generator, final String reqLine)
    {
        // Expected format: "{METHOD} {URI}{?QUERY} {PROTOCOL}"
        // Example: "GET /api/v1/foo?tenant=123 HTTP/2.0"
        final int firstSpace = reqLine.indexOf(' ');
        final int lastSpace = reqLine.lastIndexOf(' ');

        if (firstSpace != -1 && lastSpace != -1 && firstSpace != lastSpace)
        {
            // 1. Method
            generator.writeStringProperty("method", reqLine.substring(0, firstSpace));

            // 2. Protocol
            generator.writeStringProperty("request_protocol", reqLine.substring(lastSpace + 1));

            // 3. Path & Query String
            final String fullUri = reqLine.substring(firstSpace + 1, lastSpace);
            final int questionMark = fullUri.indexOf('?');

            if (questionMark != -1)
            {
                final String path = fullUri.substring(0, questionMark);
                final String query = fullUri.substring(questionMark + 1);
                generator.writeStringProperty("path", path);
                generator.writeStringProperty("query_string", query);
            }
            else
            {
                generator.writeStringProperty("path", fullUri);
                writeNull("query_string");
            }
        }
        else
        {
            // Graceful fallback for completely malformed start lines
            writeNull("method");
            generator.writeStringProperty("path", reqLine);
            writeNull("query_string");
            writeNull("request_protocol");
        }
    }

    private void writeNumber(String name, long value) throws IOException
    {
        generator.writeNumberProperty(name, value);
    }

    private void writeChecksum(String name, BodyChecksum checksum) throws IOException
    {
        if (checksum.isRecorded())
        {
            generator.writeNumberProperty(name, checksum.value());
        }
        else
        {
            writeNull(name);
        }
    }

    private void writeBody(String fieldName, List<ByteBuffer> fragments) throws IOException
    {
        if (fragments != null && !fragments.isEmpty())
        {
            generator.writeName(fieldName);
            generator.writeBinary(new SequenceByteBufferInputStream(fragments), -1);
        }
        else
        {
            writeNull(fieldName);
        }
    }

    private String getHeader(GatewayHeaders headers, String key)
    {
        if (headers == null)
        {
            return null;
        }
        return Optional.ofNullable(headers.getFirst(key)).map(String::toString).orElse(null);
    }

    /**
     * Writes {@code name: null}, unless {@link #hideEmptyFields} is set, in which case the
     * property is omitted entirely rather than written as an explicit {@code null}.
     */
    private void writeNull(String name)
    {
        if (!hideEmptyFields)
        {
            generator.writeNullProperty(name);
        }
    }

    /**
     * Writes {@code name: value} for a non-null value, {@code name: null} for a null one, or
     * (when {@link #hideEmptyFields} is set) omits the property entirely instead of writing
     * the {@code null}.
     */
    private void writeString(String name, String value)
    {
        if (value != null)
        {
            generator.writeStringProperty(name, value);
        }
        else
        {
            writeNull(name);
        }
    }

    /**
     * Writes {@code name} as a JSON object, or (when {@link #hideEmptyFields} is set) omits the
     * property entirely when the map is null/empty, rather than writing an empty {@code {}}.
     */
    private void writeMap(String name, Map<?, ?> value)
    {
        if (hideEmptyFields && (value == null || value.isEmpty()))
        {
            return;
        }
        generator.writePOJOProperty(name, value);
    }
}
