package com.ethlo.r7.json;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private static final byte[] NEWLINE = "\n".getBytes(StandardCharsets.UTF_8);
    private final JsonGenerator generator;
    private final OutputStream out;
    private final boolean hideEmptyFields;

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

        this.generator = mapper.createGenerator(out);
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
            generator.writeStartObject();

            // --- Metadata ---
            generator.writeStringProperty("gateway_request_id", exchange.getRequestId());
            writeString("remote_address", Optional.ofNullable(exchange.remoteAddress()).map(InetAddress::getHostAddress).orElse(null));
            writeString("remote_address_source", Optional.ofNullable(exchange.getRemoteAddressSource()).map(Enum::toString).orElse(null));
            generator.writeStringProperty("start", ITU.formatUtcMicro(ClockSource.convertToUtc(exchange.getClientStartTs())));
            writePlainDouble(generator, "duration", exchange.getDurationNanos() / 1_000_000_000D);
            generator.writeStringProperty("end", ITU.formatUtcMicro(ClockSource.convertToUtc(exchange.getClientEndTs())));

            generator.writeBooleanProperty("was_proxied", exchange.wasProxied());

            if (exchange.wasProxied())
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
            if (exchange.wasProxied())
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
            generator.flush();

            out.write(NEWLINE);
            out.flush();
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to write debug JSON", e);
        }
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