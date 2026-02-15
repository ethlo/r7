package com.ethlo.venturi.core.storage.json;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.json.JsonMapper;
import com.ethlo.venturi.core.storage.mmap.ExchangeCompletionListener;
import com.ethlo.venturi.core.storage.mmap.JournalExchange;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ClickHouseJsonEachRowWriter implements ExchangeCompletionListener
{
    private final JsonGenerator generator;
    private final WritableByteChannel channel;
    private static final byte[] NEWLINE = "\n".getBytes(StandardCharsets.UTF_8);

    public ClickHouseJsonEachRowWriter(OutputStream out)
    {
        // Jackson 3: Immutable builder
        this.generator = JsonMapper.builder()
                .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
                .build()
                .createGenerator(out);

        this.channel = Channels.newChannel(out);
    }

    @Override
    public void onComplete(JournalExchange exchange)
    {
        generator.writeStartObject();

        // --- Identification & Timing ---
        generator.writeStringProperty("id", exchange.getReqId());
        generator.writeNumberProperty("ts", exchange.getTimestamp());
        generator.writeNumberProperty("dur_ns", exchange.getDurationNanos());

        // --- Request Info ---
        generator.writeStringProperty("req_line", exchange.getRequestStartLine());
        generator.writePOJOProperty("req_h", exchange.getRequestHeaders());

        // --- Response Info ---
        generator.writeNumberProperty("status", exchange.getStatus());
        generator.writeStringProperty("res_line", exchange.getResponseStartLine());
        generator.writePOJOProperty("res_h", exchange.getResponseHeaders());

        // --- Throughput Metrics ---
        generator.writeNumberProperty("bytes_out", exchange.getBytesSent());
        generator.writeNumberProperty("bytes_in", exchange.getBytesReceived());

        // --- Payloads (Streamed Zero-Copy) ---
        writeBody("req_b", exchange.getRequestBodyFragments());
        writeBody("res_b", exchange.getResponseBodyFragments());

        generator.writeEndObject();
        generator.flush();

        try
        {
            // The mandatory JSONEachRow separator
            channel.write(ByteBuffer.wrap(NEWLINE));
        }
        catch (Exception e)
        {
            throw new RuntimeException("Final newline write failed", e);
        }
    }

    private void writeBody(String fieldName, List<ByteBuffer> fragments)
    {
        if (fragments == null || fragments.isEmpty())
        {
            generator.writeNullProperty(fieldName);
            return;
        }

        generator.writeName(fieldName);
        generator.writeRaw(":");
        generator.writeRaw("\"");
        generator.flush();

        for (ByteBuffer fragment : fragments)
        {
            try
            {
                // We use duplicate() to avoid messing with the Tailer's position
                channel.write(fragment.duplicate());
            }
            catch (Exception e)
            {
                throw new RuntimeException("Zero-copy payload streaming failed", e);
            }
        }

        generator.writeRaw("\"");
    }
}