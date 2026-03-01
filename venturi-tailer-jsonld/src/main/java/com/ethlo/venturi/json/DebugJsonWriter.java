package com.ethlo.venturi.json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.ethlo.time.ITU;
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
        if (prettyPrint)
        {
            //this.generator = generator.getPrettyPrinter();
        }
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
            generator.writeStringProperty("gateway_request_id", exchange.getRequestId().toString());
            generator.writeStringProperty("timestamp", ITU.formatUtcMilli(OffsetDateTime.ofInstant(Instant.ofEpochMilli(exchange.getTimestamp()), ZoneOffset.UTC)));
            writePlainDouble(generator, "duration", exchange.getDurationNanos() / 1_000_000_000D);

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
            //out.flush();
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
            writeRequestLine(generator, reqLine);
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

    private void writeRequestLine(final JsonGenerator generator, final CharSequence reqLine) throws IOException
    {
        if (reqLine == null)
        {
            return;
        }

        final int len = reqLine.length();
        int start = 0;
        int partIndex = 0;

        for (int i = 0; i < len; i++)
        {
            if (reqLine.charAt(i) == ' ')
            {
                writePart(generator, partIndex, reqLine, start, i);
                start = i + 1;
                partIndex++;
                if (partIndex > 1)
                {
                    // We have method and path, we can stop if we don't care about protocol
                    return;
                }
            }
        }

        // Handle the last part if no trailing space
        if (start < len)
        {
            writePart(generator, partIndex, reqLine, start, len);
        }
    }

    private void writePart(final JsonGenerator generator, final int index, final CharSequence seq, final int start, final int end) throws IOException
    {
        final String fieldName = (index == 0) ? "method" : "path";

        // For TRUE zero-allocation, use generator.writeRawValue or a custom Serializer.
        generator.writeStringProperty(fieldName, seq.subSequence(start, end).toString());
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