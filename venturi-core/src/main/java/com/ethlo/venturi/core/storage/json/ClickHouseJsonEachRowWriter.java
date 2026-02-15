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

        generator.writeStringProperty("id", exchange.getReqId()); // 'Property' suffix
        generator.writeNumberProperty("ts", exchange.getTimestamp());
        generator.writePOJOProperty("req_h", exchange.getRequestHeaders());

        writeBody("req_b", exchange.getRequestBodyFragments());
        writeBody("res_b", exchange.getResponseBodyFragments());

        generator.writeEndObject();
        generator.flush(); // Crucial: sync Jackson's state before manual channel writes

        try
        {
            channel.write(ByteBuffer.wrap(NEWLINE));
        }
        catch (Exception e)
        {
            throw new RuntimeException("Stream failure", e);
        }
    }

    private void writeBody(String fieldName, List<ByteBuffer> fragments)
    {
        if (fragments == null || fragments.isEmpty())
        {
            generator.writeNullProperty(fieldName);
            return;
        }

        // 1. Write the key using the new Jackson 3 method name
        generator.writeName(fieldName);

        // 2. To avoid the state machine error, write a placeholder or use writeRaw
        // Since we are targeting ClickHouse, we use raw quotes and stream the bytes
        generator.writeRaw(":"); // Manually add separator
        generator.writeRaw("\""); // Open quote
        generator.flush(); // Ensure ":" and "\"" are in the output stream

        for (ByteBuffer fragment : fragments)
        {
            try
            {
                // Physical zero-copy write of the body fragment
                channel.write(fragment.duplicate());
            }
            catch (Exception e)
            {
                throw new RuntimeException("Channel error", e);
            }
        }

        generator.writeRaw("\""); // Close quote
    }
}