package com.ethlo.venturi.core.storage.mmap;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VenturiJournalReader implements JournalEventListener
{
    private static final Logger logger = LoggerFactory.getLogger(VenturiJournalReader.class);

    public static void main(String[] args) throws IOException
    {
        if (args.length == 0)
        {
            logger.error("Usage: VenturiJournalReader <path-to-journal-file>");
            return;
        }

        Path journalPath = Paths.get(args[0]);
        new VenturiJournalReader().decode(journalPath);
    }

    public void decode(Path journalPath) throws IOException
    {
        try (RandomAccessFile raf = new RandomAccessFile(journalPath.toFile(), "r"))
        {
            long size = raf.length();
            MappedByteBuffer buffer = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, size);

            if (buffer.hasRemaining())
            {
                byte version = buffer.get();
                if (version != Marker.VERSION)
                {
                    logger.warn("Warning: Unexpected version byte: {}", version);
                }
            }

            while (buffer.hasRemaining())
            {
                byte marker = buffer.get();
                if (marker == 0)
                {
                    break;
                }

                parseEvent(marker, buffer);
            }
        }
    }

    private void parseEvent(byte marker, ByteBuffer buffer)
    {
        if (marker == Marker.BEGIN)
        {
            int dir = buffer.getInt();
            String reqId = readString(buffer);
            String startLine = readString(buffer);
            Map<String, String> headers = readHeaders(buffer);
            onBegin(dir, reqId, startLine, headers);
        }
        else if (marker == Marker.BODY)
        {
            String reqId = readString(buffer);
            int bodyLen = buffer.getInt();
            if (bodyLen > 0)
            {
                ByteBuffer body = buffer.slice();
                body.limit(bodyLen);
                onBody(reqId, body);
                buffer.position(buffer.position() + bodyLen);
            }
        }
        else if (marker == Marker.END)
        {
            String reqId = readString(buffer);
            long endTs = buffer.getLong();
            int status = buffer.getInt();
            long bytesSent = buffer.getLong();
            long bytesReceived = buffer.getLong();
            long durationNanos = buffer.getLong();
            onEnd(reqId, endTs, status, bytesSent, bytesReceived, durationNanos);
        }
    }

    private Map<String, String> readHeaders(ByteBuffer buffer)
    {
        int count = buffer.getInt();
        if (count <= 0)
        {
            return Collections.emptyMap();
        }
        Map<String, String> headers = new HashMap<>(count);
        for (int i = 0; i < count; i++)
        {
            headers.put(readString(buffer), readString(buffer));
        }
        return headers;
    }

    private String readString(ByteBuffer buffer)
    {
        int len = buffer.getInt();
        if (len <= 0)
        {
            return len == 0 ? "" : null;
        }
        byte[] bytes = new byte[len];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void onBegin(int direction, String reqId, String startLine, Map<String, String> headers)
    {
        logger.info("[{}] BEGIN - Dir: {}", reqId, direction);
        logger.info("> {}", startLine);
        headers.forEach((k, v) -> logger.info("  {}: {}", k, v));
    }

    @Override
    public void onBody(String reqId, ByteBuffer body)
    {
        logger.info("[{}] BODY ({} bytes)", reqId, body.remaining());
    }

    @Override
    public void onEnd(String reqId, long timestamp, int status, long bytesSent, long bytesReceived, long durationNanos)
    {
        logger.info("[{}] COMPLETED - Status: {}, Sent: {}, Recv: {}, Dur: {}ns",
                reqId, status, bytesSent, bytesReceived, durationNanos);
    }
}