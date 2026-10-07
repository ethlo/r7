package com.ethlo.r7.json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.api.GatewayAttributes;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.time.ClockSource;
import com.ethlo.r7.util.GatewayUtils;
import com.ethlo.time.ITU;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes each exchange as one JSON object on one line (JSON Lines). The fields are specified in
 * {@code docs/journaling.md}, "The JSON line"; {@code JsonLineSpecTest} holds this writer to it.
 */
public class JsonLinesWriter implements ExchangeCompletionListener
{
    private static final Logger logger = LoggerFactory.getLogger(JsonLinesWriter.class);
    private static final byte[] NEWLINE = "\n".getBytes(StandardCharsets.UTF_8);

    /**
     * The gateway's attribute names for the route and the upstream targets tried (set in
     * {@code GatewayPipeline}). They are written as fields of their own, {@code route_id} and
     * {@code upstream_request.targets}, and left out of {@code attributes}.
     */
    static final String ROUTE_ID_ATTRIBUTE = "gateway.route.id";
    static final String UPSTREAM_TARGET_ATTRIBUTE = "gateway.target";

    private final OutputStream out;
    private final boolean bodies;

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

    public JsonLinesWriter(final OutputStream out)
    {
        this(out, true);
    }

    /**
     * @param bodies write captured bodies into the line when no WARC file holds them; when
     *               false a line never carries a body, and {@code body_bytes} and the checksums
     *               still show that there was one. With this and the WARC output's bodies both
     *               off, no body is stored anywhere.
     */
    public JsonLinesWriter(final OutputStream out, final boolean bodies)
    {
        this.out = out;
        this.bodies = bodies;
        final JsonMapper mapper = JsonMapper.builder()
                .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
                .configure(JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS, false) // Ensure numbers stay as numbers
                .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.ALWAYS))
                .build();

        // Bound to the scratch buffer for the writer's whole lifetime - see the field
        // javadoc for why this, and not out, is what the generator is allowed to touch.
        this.generator = mapper.createGenerator(scratch);
    }

    /**
     * Seconds, as a JSON number with six decimals (microseconds). Formatted without the JVM's
     * locale: {@code String.format} writes a decimal comma under a locale such as {@code nb_NO},
     * which is not JSON.
     */
    private void writeDuration(final String name, final long nanos)
    {
        generator.writeName(name);
        generator.writeRawValue(BigDecimal.valueOf(nanos, 9).setScale(6, RoundingMode.HALF_UP).toPlainString());
    }

    @Override
    public void onComplete(JournalExchange exchange)
    {
        writeComplete(exchange, null);
    }

    /**
     * Where an exchange's WARC records are: the sealed file's name, the offset of the first
     * record's Zstandard frame, and the compressed length of the exchange's records.
     */
    public record WarcPointer(String file, long offset, long length)
    {
    }

    /**
     * Writes a complete exchange whose records a WARC file holds, bodies included. The line
     * points at them instead of carrying the bodies: they are stored once, where a WARC reader
     * expects them.
     *
     * @param warc where the records are, or {@code null} when no WARC file holds them; the
     *             bodies are then written into the line
     */
    public void writeComplete(final JournalExchange exchange, final WarcPointer warc)
    {
        writeComplete(exchange, warc, true);
    }

    /**
     * Writes a complete exchange, pointing at its WARC records when there are any. A body goes
     * into the line when bodies are on here and no WARC record stores it, so it is stored once
     * at most.
     *
     * @param warc         where the records are, or {@code null} when no WARC file holds them
     * @param bodiesInWarc whether those records store the bodies; ignored without {@code warc}
     */
    public void writeComplete(final JournalExchange exchange, final WarcPointer warc, final boolean bodiesInWarc)
    {
        try
        {
            writeExchangeObject(exchange, null, warc, bodies && (warc == null || !bodiesInWarc));
            flushRecord();
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to write JSON line", e);
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
            writeExchangeObject(exchange, reason, null, bodies);
            flushRecord();
        }
        catch (IOException e)
        {
            // Counted after the call, like every other counter here and in the reassembler
            // that calls this: a refusal is retried whole (FORMAT.md §6), and counting on
            // every attempt would report more incomplete records than were ever actually
            // written.
            throw new RuntimeException("Failed to write JSON line", e);
        }

        final long total = incompleteEndCount.incrementAndGet();
        if (total == 1)
        {
            logger.warn("Exchange {} ended but was not a complete record ({}); emitted with "
                            + "\"incomplete\" set. Further occurrences are counted, not logged.",
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
            writeExchangeObject(exchange, reason, null, bodies);
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
     * {@link #scratch}), without touching {@link #out}. One object per exchange, one object
     * per leg of it:
     * <pre>
     * {
     *   "request_id": "...",
     *   "incomplete": "TIMED_OUT",            only when this is not a complete record
     *   "route_id": "...",
     *   "start": "...", "end": "...", "duration": 0.012345,
     *   "remote_address": "...", "remote_address_source": "SOCKET",
     *   "client_request":    {level, method, path, query, protocol, headers, header_bytes, body_bytes, body, checksum},
     *   "upstream_request":  {level, method, path, query, protocol, targets, headers},
     *   "upstream_response": {level, protocol, status, reason, headers, start, first_byte, end, duration},
     *   "client_response":   {level, protocol, status, reason, headers, header_bytes, body_bytes, body, checksum},
     *   "attributes": {...},
     *   "warc": {file, offset, length}        only when a WARC file holds the exchange; no bodies when it stores them
     * }
     * </pre>
     * The exchange's timing is at the top level, the upstream round trip's in
     * {@code upstream_response}. Nothing is written that another field already says: whether the
     * request was proxied is whether the upstream objects are there, an error is a status, and
     * the content type is a header; {@code duration} is kept beside {@code start} and {@code end}
     * because it is what queries use. The upstream objects are present only for a proxied exchange.
     * {@code observed_checksum} appears beside {@code checksum} only when the body read back
     * does not match what the gateway recorded.
     * <p>
     * Without an end event (an abandoned exchange) timing, status, sizes and checksums are
     * unknown rather than zero (README.md §11.4), and are left out. A field with no value is
     * always left out rather than written as {@code null}.
     *
     * @param reason null for a complete record, otherwise why it is not one
     * @param warc        where a WARC file holds the exchange, written as {@code "warc"}; null for none
     * @param writeBodies whether the captured bodies go into this line
     */
    private void writeExchangeObject(final JournalExchange exchange, final IncompleteReason reason, final WarcPointer warc,
                                     final boolean writeBodies) throws IOException
    {
        final boolean hasEndEvent = reason == null || reason.hasEndEvent();

        generator.writeStartObject();

        generator.writeStringProperty("request_id", exchange.getRequestId());
        if (reason != null)
        {
            generator.writeStringProperty("incomplete", reason.name());
        }
        final GatewayAttributes attributes = exchange.getAttributes();
        writeString("route_id", attributes != null ? attributes.getFirst(ROUTE_ID_ATTRIBUTE) : null);

        if (hasEndEvent)
        {
            generator.writeStringProperty("start", timestamp(exchange.getClientStartTs()));
            generator.writeStringProperty("end", timestamp(exchange.getClientEndTs()));
            writeDuration("duration", exchange.getDurationNanos());
        }
        writeString("remote_address", Optional.ofNullable(exchange.remoteAddress()).map(InetAddress::getHostAddress).orElse(null));
        writeString("remote_address_source", Optional.ofNullable(exchange.getRemoteAddressSource()).map(Enum::toString).orElse(null));

        // wasProxied() reads proxyStartTs, which only the end event sets; without one, an
        // upstream leg having been journaled is the evidence there is.
        final boolean proxied = hasEndEvent
                ? exchange.wasProxied()
                : (exchange.getUpstreamRequestStartLine() != null || exchange.getUpstreamResponseStartLine() != null);

        // client_request
        generator.writeName("client_request");
        generator.writeStartObject();
        writeLevel(exchange.getClientRequestLevel());
        writeRequestLine(exchange.getClientRequestStartLine());
        writeMap("headers", GatewayUtils.toMap(exchange.getClientRequestHeaders()));
        writeSizes(hasEndEvent, exchange.getRequestHeaderBytes(), exchange.getRequestBodyBytes());
        if (writeBodies)
        {
            writeBody(exchange.getRequestBodyFragments());
        }
        writeChecksums(exchange.getJournaledRequestChecksum(), exchange.getObservedRequestChecksum());
        generator.writeEndObject();

        if (proxied)
        {
            generator.writeName("upstream_request");
            generator.writeStartObject();
            writeLevel(exchange.getUpstreamRequestLevel());
            writeRequestLine(exchange.getUpstreamRequestStartLine());
            writeTargets(attributes);
            writeMap("headers", GatewayUtils.toMap(exchange.getUpstreamRequestHeaders()));
            generator.writeEndObject();

            generator.writeName("upstream_response");
            generator.writeStartObject();
            writeLevel(exchange.getUpstreamResponseLevel());
            writeResponseLine(exchange.getUpstreamResponseStartLine(), null);
            writeMap("headers", GatewayUtils.toMap(exchange.getUpstreamResponseHeaders()));
            if (hasEndEvent)
            {
                generator.writeStringProperty("start", timestamp(exchange.getProxyStartTs()));
                generator.writeStringProperty("first_byte", timestamp(exchange.getProxyFirstByteReceivedTs()));
                generator.writeStringProperty("end", timestamp(exchange.getProxyEndTs()));
                writeDuration("duration", exchange.getProxyDurationNanos());
            }
            generator.writeEndObject();
        }

        // client_response: the status comes from the end event, which every journal level
        // records, so it is there even when the start line was not journaled.
        generator.writeName("client_response");
        generator.writeStartObject();
        writeLevel(exchange.getClientResponseLevel());
        writeResponseLine(exchange.getClientResponseStartLine(), hasEndEvent ? exchange.getStatus() : null);
        writeMap("headers", GatewayUtils.toMap(exchange.getClientResponseHeaders()));
        writeSizes(hasEndEvent, exchange.getResponseHeaderBytes(), exchange.getResponseBodyBytes());
        if (writeBodies)
        {
            writeBody(exchange.getResponseBodyFragments());
        }
        writeChecksums(exchange.getJournaledResponseChecksum(), exchange.getObservedResponseChecksum());
        generator.writeEndObject();

        final Map<String, List<String>> otherAttributes = new HashMap<>(GatewayUtils.toMap(attributes));
        otherAttributes.remove(ROUTE_ID_ATTRIBUTE);
        otherAttributes.remove(UPSTREAM_TARGET_ATTRIBUTE);
        writeMap("attributes", otherAttributes);

        if (warc != null)
        {
            generator.writeName("warc");
            generator.writeStartObject();
            generator.writeStringProperty("file", warc.file());
            generator.writeNumberProperty("offset", warc.offset());
            generator.writeNumberProperty("length", warc.length());
            generator.writeEndObject();
        }

        generator.writeEndObject();
    }

    private static String timestamp(final long clockTs)
    {
        return ITU.formatUtcMicro(ClockSource.convertToUtc(clockTs));
    }

    private void writeLevel(final JournalLevel level)
    {
        generator.writeStringProperty("level", level != null ? level.name() : JournalLevel.NONE.name());
    }

    private void writeSizes(final boolean known, final long headerBytes, final long bodyBytes)
    {
        if (known)
        {
            generator.writeNumberProperty("header_bytes", headerBytes);
            generator.writeNumberProperty("body_bytes", bodyBytes);
        }
    }

    /**
     * The upstream targets tried, in order; the last one is the one whose response was
     * recorded.
     */
    private void writeTargets(final GatewayAttributes attributes)
    {
        if (attributes == null || !attributes.contains(UPSTREAM_TARGET_ATTRIBUTE))
        {
            return;
        }
        generator.writeName("targets");
        generator.writeStartArray();
        for (final String target : attributes.getAll(UPSTREAM_TARGET_ATTRIBUTE))
        {
            generator.writeString(target);
        }
        generator.writeEndArray();
    }

    /**
     * The checksum the gateway recorded, and the one computed from the body read back only
     * when they differ: a match is the normal case and says nothing new. Written labelled with
     * the algorithm, {@code crc32c:} and eight hex digits, the {@code algorithm:value} shape the
     * line's fingerprints have.
     */
    private void writeChecksums(final BodyChecksum journaled, final BodyChecksum observed)
    {
        if (journaled.isRecorded())
        {
            generator.writeStringProperty("checksum", crc32c(journaled));
            if (observed.isRecorded() && observed.value() != journaled.value())
            {
                generator.writeStringProperty("observed_checksum", crc32c(observed));
            }
        }
    }

    private static String crc32c(final BodyChecksum checksum)
    {
        return "crc32c:" + HexFormat.of().toHexDigits((int) checksum.value());
    }

    /**
     * @param status the status from the end event, which wins over the start line's; {@code null}
     *               to take it from the start line
     */
    private void writeResponseLine(final String line, final Integer status)
    {
        // "{PROTOCOL} {CODE} {REASON}", e.g. "HTTP/1.1 503 Service Unavailable"
        String protocol = null;
        String code = null;
        String reason = null;
        if (line != null)
        {
            final int firstSpace = line.indexOf(' ');
            if (firstSpace < 0)
            {
                code = line;
            }
            else
            {
                protocol = line.substring(0, firstSpace);
                final int secondSpace = line.indexOf(' ', firstSpace + 1);
                code = secondSpace < 0 ? line.substring(firstSpace + 1) : line.substring(firstSpace + 1, secondSpace);
                reason = secondSpace < 0 ? null : line.substring(secondSpace + 1);
            }
        }

        writeString("protocol", protocol);
        if (status != null)
        {
            generator.writeNumberProperty("status", status);
        }
        else
        {
            writeStatusCode(code);
        }
        writeString("reason", reason);
    }

    /**
     * A start line whose code does not parse is malformed, so the status is left out rather
     * than smuggled through as a string.
     */
    private void writeStatusCode(final String code)
    {
        try
        {
            if (code != null)
            {
                generator.writeNumberProperty("status", Integer.parseInt(code));
                return;
            }
        }
        catch (final NumberFormatException e)
        {
            // left out, as malformed
        }
    }

    private void writeRequestLine(final String line)
    {
        // "{METHOD} {URI}{?QUERY} {PROTOCOL}", e.g. "GET /api/v1/foo?tenant=123 HTTP/1.1"
        if (line == null)
        {
            return;
        }

        final int firstSpace = line.indexOf(' ');
        final int lastSpace = line.lastIndexOf(' ');
        if (firstSpace < 0 || firstSpace == lastSpace)
        {
            // Malformed: keep what there is rather than drop it.
            generator.writeStringProperty("path", line);
            return;
        }

        generator.writeStringProperty("method", line.substring(0, firstSpace));
        final String uri = line.substring(firstSpace + 1, lastSpace);
        final int questionMark = uri.indexOf('?');
        generator.writeStringProperty("path", questionMark < 0 ? uri : uri.substring(0, questionMark));
        writeString("query", questionMark < 0 ? null : uri.substring(questionMark + 1));
        generator.writeStringProperty("protocol", line.substring(lastSpace + 1));
    }

    private void writeBody(final List<ByteBuffer> fragments) throws IOException
    {
        if (fragments != null && !fragments.isEmpty())
        {
            generator.writeName("body");
            generator.writeBinary(new SequenceByteBufferInputStream(fragments), -1);
        }
    }

    /**
     * Writes {@code name: value}, or nothing for a {@code null} value.
     */
    private void writeString(final String name, final String value)
    {
        if (value != null)
        {
            generator.writeStringProperty(name, value);
        }
    }

    /**
     * Writes {@code name} as a JSON object whose values are always arrays, or nothing for an
     * empty map: a header sent once and one sent twice have the same type.
     */
    private void writeMap(final String name, final Map<String, List<String>> value)
    {
        if (value == null || value.isEmpty())
        {
            return;
        }
        generator.writePOJOProperty(name, value);
    }
}
