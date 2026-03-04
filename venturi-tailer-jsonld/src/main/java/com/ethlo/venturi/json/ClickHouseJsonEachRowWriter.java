package com.ethlo.venturi.json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.journal.api.ExchangeCompletionListener;
import com.ethlo.venturi.journal.api.JournalExchange;
import com.ethlo.venturi.journal.api.JournalLevel;
import com.ethlo.venturi.util.GatewayUtils;
import com.ethlo.venturi.util.constants.HttpHeaders;
import com.ethlo.venturi.util.constants.HttpStatuses;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.json.JsonMapper;

public class ClickHouseJsonEachRowWriter implements ExchangeCompletionListener
{
    private static final byte[] NEWLINE = "\n".getBytes(StandardCharsets.UTF_8);
    private final JsonGenerator generator;
    private final OutputStream out;

    public ClickHouseJsonEachRowWriter(OutputStream out)
    {
        this.out = out;
        this.generator = JsonMapper.builder()
                .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
                .changeDefaultPropertyInclusion(inclusion ->
                        inclusion.withValueInclusion(JsonInclude.Include.NON_EMPTY))
                .build()
                .createGenerator(out);
    }

    @Override
    public void onComplete(JournalExchange exchange)
    {
        try
        {
            generator.writeStartObject();

            // --- Meta & Levels ---
            writeString("gateway_request_id", exchange.getRequestId().toString());
            generator.writeNumberProperty("timestamp", exchange.getClientStartTs());
            generator.writeNumberProperty("duration", exchange.getDurationNanos() / 1_000_000_000D);

            writeJournalLevels(exchange);

            // --- The Four Forensic Slices ---
            writeRequestSlice(exchange.getClientRequestStartLine(), exchange.getClientRequestHeaders(), "client");
            writeRequestSlice(exchange.getUpstreamRequestStartLine(), exchange.getUpstreamRequestHeaders(), "upstream");
            writeResponseSlice(exchange.getUpstreamResponseStartLine(), exchange.getUpstreamResponseHeaders(), "upstream");
            writeResponseSlice(exchange.getClientResponseStartLine(), exchange.getClientResponseHeaders(), "client");

            // --- Metrics ---
            int status = exchange.getStatus();
            generator.writeNumberProperty("status", status);
            generator.writeNumberProperty("is_error", status >= HttpStatuses.BAD_REQUEST ? 1 : 0);

            writeNumber("request_header_bytes", exchange.getRequestHeaderBytes());
            writeNumber("request_body_bytes", exchange.getRequestBodyBytes());
            writeNumber("response_header_bytes", exchange.getRequestHeaderBytes());
            writeNumber("response_body_bytes", exchange.getRequestBodyBytes());

            writeNumber("request_crc32", exchange.getJournaledRequestCrc32());
            writeNumber("response_crc32", exchange.getJournaledResponseCrc32());

            // --- Payloads ---
            writeBody("request_body", exchange.getRequestBodyFragments());
            writeBody("response_body", exchange.getResponseBodyFragments());

            // --- Contextual Attributes ---
            writeMap("attributes", GatewayUtils.toMap(exchange.getAttributes()));

            generator.writeEndObject();
            generator.flush();

            out.write(NEWLINE);
            out.flush();
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to write JSON row", e);
        }
    }

    private void writeJournalLevels(JournalExchange exchange) throws IOException
    {
        writeLevel("client_request_level", exchange.getClientRequestLevel());
        writeLevel("upstream_request_level", exchange.getUpstreamRequestLevel());
        writeLevel("upstream_response_level", exchange.getUpstreamResponseLevel());
        writeLevel("client_response_level", exchange.getClientResponseLevel());
    }

    private void writeRequestSlice(CharSequence startLine, GatewayHeaders headers, String prefix) throws IOException
    {
        if (startLine != null)
        {
            final String[] parts = startLine.toString().split("\\s+");
            writeString(prefix + "_method", parts.length > 0 ? parts[0] : null);
            writeString(prefix + "_path", parts.length > 1 ? parts[1] : null);
        }
        writeMap(prefix + "_request_headers", GatewayUtils.toMap(headers));
        writeString(prefix + "_host", getHeader(headers, HttpHeaders.HOST));
        writeString(prefix + "_user_agent", getHeader(headers, HttpHeaders.USER_AGENT));
    }

    private void writeResponseSlice(CharSequence startLine, GatewayHeaders headers, String prefix) throws IOException
    {
        writeMap(prefix + "_response_headers", GatewayUtils.toMap(headers));
        writeString(prefix + "_content_type", getHeader(headers, HttpHeaders.CONTENT_TYPE));
    }

    /* ============================================================
       SPARSE WRITER HELPERS
       ============================================================ */

    private void writeLevel(String name, JournalLevel level) throws IOException
    {
        if (level != null && level != JournalLevel.NONE)
        {
            generator.writeStringProperty(name, level.name());
        }
    }

    private void writeString(String name, String value) throws IOException
    {
        if (value != null && !value.isEmpty())
        {
            generator.writeStringProperty(name, value);
        }
    }

    private void writeNumber(String name, long value) throws IOException
    {
        if (value != 0)
        {
            generator.writeNumberProperty(name, value);
        }
    }

    private void writeMap(String name, Map<String, ?> map) throws IOException
    {
        if (map != null && !map.isEmpty())
        {
            generator.writePOJOProperty(name, map);
        }
    }

    private void writeBody(String fieldName, List<ByteBuffer> fragments) throws IOException
    {
        if (fragments != null && !fragments.isEmpty())
        {
            generator.writeName(fieldName);
            generator.writeBinary(new SequenceByteBufferInputStream(fragments), -1);
        }
    }

    private String getHeader(GatewayHeaders headers, String key)
    {
        if (headers == null) return null;
        return Optional.ofNullable(headers.getFirst(key)).map(CharSequence::toString).orElse(null);
    }
}