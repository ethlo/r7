package com.ethlo.venturi.json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.journal.api.ExchangeCompletionListener;
import com.ethlo.venturi.journal.api.JournalExchange;
import com.ethlo.venturi.util.GatewayUtils;
import com.ethlo.venturi.util.constants.HttpHeaders;
import com.ethlo.venturi.util.constants.HttpStatuses;
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
            generator.writeStringProperty("gateway_request_id", exchange.getRequestId().toString());
            generator.writeNumberProperty("timestamp", exchange.getTimestamp());
            generator.writeNumberProperty("duration_ms", exchange.getDurationNanos() / 1_000_000);

            writeJournalLevels(exchange);

            // --- Slice 1: Client Request (Pristine) ---
            writeRequestSlice(exchange.getClientRequestStartLine(), exchange.getClientRequestHeaders(), "client");

            // --- Slice 2: Upstream Request (Mutated) ---
            writeRequestSlice(exchange.getUpstreamRequestStartLine(), exchange.getUpstreamRequestHeaders(), "upstream");

            // --- Slice 3: Upstream Response (Raw Backend) ---
            writeResponseSlice(exchange.getUpstreamResponseStartLine(), exchange.getUpstreamResponseHeaders(), "upstream");

            // --- Slice 4: Client Response (Final Egress) ---
            writeResponseSlice(exchange.getClientResponseStartLine(), exchange.getClientResponseHeaders(), "client");

            // --- Metrics & Status ---
            int status = exchange.getStatus();
            generator.writeNumberProperty("status", status);
            generator.writeNumberProperty("is_error", status >= HttpStatuses.BAD_REQUEST ? 1 : 0);
            generator.writeNumberProperty("bytes_sent", exchange.getBytesSent());
            generator.writeNumberProperty("bytes_received", exchange.getBytesReceived());

            // --- Forensic Checksums ---
            generator.writeNumberProperty("request_crc32", exchange.getRequestCrc32());
            generator.writeNumberProperty("response_crc32", exchange.getResponseCrc32());

            // --- Payloads ---
            writeBody("request_body", exchange.getRequestBodyFragments());
            writeBody("response_body", exchange.getResponseBodyFragments());

            // --- Attributes (Internal Context) ---
            generator.writePOJOProperty("attributes", GatewayUtils.toMap(exchange.getAttributes()));

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
        generator.writeStringProperty("client_request_level", exchange.getClientRequestLevel() != null ? exchange.getClientRequestLevel().name() : null);
        generator.writeStringProperty("upstream_request_level", exchange.getUpstreamRequestLevel() != null ? exchange.getUpstreamRequestLevel().name() : null);
        generator.writeStringProperty("upstream_response_level", exchange.getUpstreamResponseLevel() != null ? exchange.getUpstreamResponseLevel().name() : null);
        generator.writeStringProperty("client_response_level", exchange.getClientResponseLevel() != null ? exchange.getClientResponseLevel().name() : null);
    }

    private void writeRequestSlice(CharSequence startLine, GatewayHeaders headers, String prefix) throws IOException
    {
        if (startLine != null)
        {
            final String[] parts = startLine.toString().split("\\s+");
            generator.writeStringProperty(prefix + "_method", parts.length > 0 ? parts[0] : null);
            generator.writeStringProperty(prefix + "_path", parts.length > 1 ? parts[1] : null);
        }
        generator.writePOJOProperty(prefix + "_request_headers", GatewayUtils.toMap(headers));

        // Flattened common search columns
        generator.writeStringProperty(prefix + "_host", getHeader(headers, HttpHeaders.HOST));
        generator.writeStringProperty(prefix + "_user_agent", getHeader(headers, HttpHeaders.USER_AGENT));
    }

    private void writeResponseSlice(CharSequence startLine, GatewayHeaders headers, String prefix) throws IOException
    {
        generator.writePOJOProperty(prefix + "_response_headers", GatewayUtils.toMap(headers));
        generator.writeStringProperty(prefix + "_content_type", getHeader(headers, HttpHeaders.CONTENT_TYPE));
    }

    private void writeBody(String fieldName, List<ByteBuffer> fragments) throws IOException
    {
        if (fragments == null || fragments.isEmpty())
        {
            generator.writeNullProperty(fieldName);
            return;
        }
        generator.writeName(fieldName);
        generator.writeBinary(new SequenceByteBufferInputStream(fragments), -1);
    }

    private String getHeader(GatewayHeaders headers, String key)
    {
        if (headers == null) return null;
        return Optional.ofNullable(headers.getFirst(key)).map(CharSequence::toString).orElse(null);
    }
}