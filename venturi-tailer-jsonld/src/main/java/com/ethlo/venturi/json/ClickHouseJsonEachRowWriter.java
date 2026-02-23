package com.ethlo.venturi.json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ethlo.venturi.journal.api.ExchangeCompletionListener;
import com.ethlo.venturi.journal.api.JournalExchange;
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

            // --- Timing & IDs ---
            // ClickHouse DateTime64(3) accepts Unix Millis
            generator.writeNumberProperty("timestamp", exchange.getTimestamp());
            generator.writeStringProperty("gateway_request_id", exchange.getRequestId());
            generator.writeNumberProperty("response_time", exchange.getDurationNanos() / 1_000_000); // ns to ms

            // --- Request Info ---
            final String[] parts = exchange.getRequestStartLine().split("\\s+");
            if (parts.length >= 2)
            {
                generator.writeStringProperty("method", parts[0]); // GET, POST, etc.
                generator.writeStringProperty("path", parts[1]);   // /api/v1/resource
            }
            generator.writePOJOProperty("request_headers", exchange.getRequestHeaders());

            // Helper for specific schema columns
            generator.writeStringProperty("request_content_type", getHeader(exchange.getRequestHeaders(), "Content-Type"));
            generator.writeStringProperty("user_agent", getHeader(exchange.getRequestHeaders(), "User-Agent"));
            generator.writeStringProperty("host", getHeader(exchange.getRequestHeaders(), "Host"));

            // --- Response Info ---
            int status = exchange.getStatus();
            generator.writeNumberProperty("status", status);
            generator.writeNumberProperty("is_error", status >= 400 ? 1 : 0);
            generator.writePOJOProperty("response_headers", exchange.getResponseHeaders());
            generator.writeStringProperty("response_content_type", getHeader(exchange.getResponseHeaders(), "Content-Type"));

            // --- Size Metrics ---
            generator.writeNumberProperty("request_body_size", exchange.getBytesReceived());
            generator.writeNumberProperty("response_body_size", exchange.getBytesSent());

            // --- Payloads (Streaming Binary) ---
            writeBody("request_body", exchange.getRequestBodyFragments());
            writeBody("response_body", exchange.getResponseBodyFragments());

            // --- Missing Route Info (Placeholders if not in JournalExchange) ---
            // generator.writeStringProperty("route_id", ...);
            // generator.writeStringProperty("route_uri", ...);

            generator.writeEndObject();
            generator.flush();

            // JSONEachRow requires a newline after every object
            out.write(NEWLINE);
            out.flush();
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to write JSON row", e);
        }
    }

    private void writeBody(String fieldName, List<ByteBuffer> fragments) throws IOException
    {
        if (fragments == null || fragments.isEmpty())
        {
            generator.writeNullProperty(fieldName);
            return;
        }
        generator.writeName(fieldName);
        // Safely encode binary data as Base64 for the String/Nullable(String) columns
        generator.writeBinary(new SequenceByteBufferInputStream(fragments), -1);
    }

    private String getHeader(Map<CharSequence, CharSequence> headers, String key)
    {
        if (headers == null)
        {
            return null;
        }
        return Optional.ofNullable(headers.get(key)).map(CharSequence::toString).orElse(null);
    }
}