package com.ethlo.r7.json;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import com.ethlo.r7.api.GatewayHeaders;
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

public class DebugJsonWriter implements ExchangeCompletionListener
{
    private static final byte[] NEWLINE = "\n".getBytes(StandardCharsets.UTF_8);
    private final JsonGenerator generator;
    private final OutputStream out;

    public DebugJsonWriter(OutputStream out, boolean prettyPrint)
    {
        this.out = out;
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
            generator.writeStringProperty("remote_address", Optional.ofNullable(exchange.remoteAddress()).map(InetAddress::getHostAddress).orElse(null));
            generator.writeStringProperty("remote_address_source", Optional.ofNullable(exchange.getRemoteAddressSource()).map(Enum::toString).orElse(null));
            generator.writeStringProperty("start", ITU.formatUtcMicro(ClockSource.convertToUtc(exchange.getClientStartTs())));
            writePlainDouble(generator, "duration", exchange.getDurationNanos() / 1_000_000_000D);
            generator.writeStringProperty("end", ITU.formatUtcMicro(ClockSource.convertToUtc(exchange.getClientEndTs())));

            generator.writeBooleanProperty("was_proxied", exchange.wasProxied());

            if (exchange.wasProxied())
            {
                generator.writeStringProperty("proxy_start", ITU.formatUtcMicro(ClockSource.convertToUtc(exchange.getProxyStartTs())));
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
            generator.writePOJOProperty("attributes", GatewayUtils.toMap(exchange.getAttributes()));

            generator.writeEndObject();
            generator.flush();

            out.write(NEWLINE);
            //out.flush();
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
            // Maintain strict JSON schema consistency for columnar databases
            generator.writeNullProperty("method");
            generator.writeNullProperty("path");
            generator.writeNullProperty("query_string");
            generator.writeNullProperty("protocol");
        }

        generator.writePOJOProperty("request_headers", GatewayUtils.toMap(reqHeaders));

        // Response half
        generator.writeStringProperty("response_journal_level", resLevel != null ? resLevel.name() : "NONE");
        generator.writePOJOProperty("response_headers", GatewayUtils.toMap(resHeaders));
        generator.writeStringProperty("content_type", getHeader(resHeaders, HttpHeaders.CONTENT_TYPE));

        writeResponseLine(generator, resLine);

        generator.writeEndObject();
    }

    private void writeResponseLine(final JsonGenerator generator, final String resLine)
    {
        // Expected format: "{PROTOCOL} {CODE} {REASON}"
        // Example: "HTTP/1.1 503 Service Unavailable"
        if (resLine == null)
        {
            generator.writeNullProperty("response_protocol");
            generator.writeNullProperty("response_status_code");
            generator.writeNullProperty("response_reason");
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
                generator.writeStringProperty("response_status_code", resLine.substring(firstSpace + 1, secondSpace));

                // 3. Reason Phrase (everything after the second space)
                generator.writeStringProperty("response_reason", resLine.substring(secondSpace + 1));
            }
            else
            {
                // Fallback if there is no reason phrase
                generator.writeStringProperty("response_status_code", resLine.substring(firstSpace + 1));
                generator.writeNullProperty("response_reason");
            }
        }
        else
        {
            // Graceful fallback for completely malformed lines
            generator.writeNullProperty("response_protocol");
            generator.writeStringProperty("response_status_code", resLine);
            generator.writeNullProperty("response_reason");
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
            generator.writeStringProperty("protocol", reqLine.substring(lastSpace + 1));

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
                generator.writeNullProperty("query_string");
            }
        }
        else
        {
            // Graceful fallback for completely malformed start lines
            generator.writeNullProperty("method");
            generator.writeStringProperty("path", reqLine);
            generator.writeNullProperty("query_string");
            generator.writeNullProperty("protocol");
        }
    }

    private void writeNumber(String name, long value) throws IOException
    {
        generator.writeNumberProperty(name, value);
    }

    private void writePart(final JsonGenerator generator, final int index, final String seq, final int start, final int end) throws IOException
    {
        final String fieldName = (index == 0) ? "method" : "path";

        generator.writeStringProperty(fieldName, seq.subSequence(start, end).toString());
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
            generator.writeNullProperty(fieldName);
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
}