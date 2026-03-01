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
import com.ethlo.venturi.journal.api.JournalLevel;
import com.ethlo.venturi.util.GatewayUtils;
import com.ethlo.venturi.util.constants.HttpHeaders;
import com.ethlo.venturi.util.constants.HttpStatuses;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamWriteFeature;
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
                // In debug mode, we want to see everything
                .changeDefaultPropertyInclusion(inclusion ->
                        inclusion.withValueInclusion(JsonInclude.Include.ALWAYS))
                .configure(SerializationFeature.INDENT_OUTPUT, prettyPrint)
                .build();

        this.generator = mapper.createGenerator(out);
        if (prettyPrint)
        {
            //this.generator = generator.getPrettyPrinter();
        }
    }

    @Override
    public void onComplete(JournalExchange exchange)
    {
        try
        {
            generator.writeStartObject();

            // --- Metadata ---
            generator.writeStringProperty("gateway_request_id", exchange.getRequestId().toString());
            generator.writeNumberProperty("timestamp", exchange.getTimestamp());
            generator.writeNumberProperty("duration_sec", exchange.getDurationNanos() / 1_000_000_000D);

            // --- Metrics ---
            final int status = exchange.getStatus();
            generator.writeNumberProperty("status", status);
            generator.writeBooleanProperty("is_error", status >= HttpStatuses.BAD_REQUEST);
            generator.writeNumberProperty("bytes_sent", exchange.getBytesSent());
            generator.writeNumberProperty("bytes_received", exchange.getBytesReceived());

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
            generator.writeName("upstream");
            writeExchangeNode(
                    exchange.getUpstreamRequestLevel(),
                    exchange.getUpstreamRequestStartLine(),
                    exchange.getUpstreamRequestHeaders(),
                    exchange.getUpstreamResponseLevel(),
                    exchange.getUpstreamResponseStartLine(),
                    exchange.getUpstreamResponseHeaders()
            );

            // --- Payload Debugging ---
            writeBody("request_body", exchange.getRequestBodyFragments());
            writeBody("response_body", exchange.getResponseBodyFragments());

            // --- Context ---
            generator.writePOJOProperty("attributes", GatewayUtils.toMap(exchange.getAttributes()));

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
            JournalLevel reqLevel, CharSequence reqLine, GatewayHeaders reqHeaders,
            JournalLevel resLevel, CharSequence resLine, GatewayHeaders resHeaders) throws IOException
    {
        generator.writeStartObject();

        // Request half
        generator.writeStringProperty("request_journal_level", reqLevel != null ? reqLevel.name() : "NONE");
        if (reqLine != null)
        {
            final String[] parts = reqLine.toString().split("\\s+");
            generator.writeStringProperty("method", parts.length > 0 ? parts[0] : null);
            generator.writeStringProperty("path", parts.length > 1 ? parts[1] : null);
        }
        else
        {
            generator.writeNullProperty("method");
            generator.writeNullProperty("path");
        }
        generator.writePOJOProperty("request_headers", GatewayUtils.toMap(reqHeaders));

        // Response half
        generator.writeStringProperty("response_journal_level", resLevel != null ? resLevel.name() : "NONE");
        generator.writePOJOProperty("response_headers", GatewayUtils.toMap(resHeaders));
        generator.writeStringProperty("content_type", getHeader(resHeaders, HttpHeaders.CONTENT_TYPE));

        generator.writeEndObject();
    }

    private void writeBody(String fieldName, List<ByteBuffer> fragments) throws IOException
    {
        if (fragments != null && !fragments.isEmpty())
        {
            generator.writeName(fieldName);
            // In debug mode, binary is Base64 encoded by Jackson
            generator.writeBinary(new SequenceByteBufferInputStream(fragments), -1);
        }
        else
        {
            generator.writeNullProperty(fieldName);
        }
    }

    private String getHeader(GatewayHeaders headers, String key)
    {
        if (headers == null) return null;
        return Optional.ofNullable(headers.getFirst(key)).map(CharSequence::toString).orElse(null);
    }
}