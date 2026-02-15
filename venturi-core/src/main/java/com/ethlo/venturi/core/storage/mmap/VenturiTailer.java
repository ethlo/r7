package com.ethlo.venturi.core.storage.mmap;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VenturiTailer
{
    private static final Logger logger = LoggerFactory.getLogger(VenturiTailer.class);
    private final Map<Path, Long> checkpoints = new HashMap<>();
    private final Path logDir;
    private final JournalEventListener listener;

    public VenturiTailer(Path logDir, JournalEventListener listener)
    {
        this.logDir = logDir;
        this.listener = listener;
    }

    public static void main(String[] args) throws InterruptedException
    {
        Path logDir = Paths.get(args.length > 0 ? args[0] : "/tmp/venturi");
        Path outputDir = Paths.get(args.length > 1 ? args[1] : "/tmp/venturi-ingest");

        VenturiTailer tailer = new VenturiTailer(logDir, new JsonFileJournalEventListener(outputDir));
        logger.info("Venturi Tailer started. Watching {}...", logDir);

        while (!Thread.currentThread().isInterrupted())
        {
            try
            {
                tailer.runTick();
            }
            catch (Exception e)
            {
                logger.error("Error during tailer tick: {}", e.getMessage(), e);
            }

            // 1-second heartbeat to keep latency low in low-traffic periods
            Thread.sleep(1000);
        }
    }

    public void runTick() throws IOException
    {
        try (Stream<Path> s = Files.list(logDir))
        {
            List<Path> targets = s.filter(p -> p.toString().endsWith(".raw") || p.toString().endsWith(".tmp"))
                    .sorted()
                    .toList();

            for (Path path : targets)
            {
                processFile(path);

                if (path.toString().endsWith(".tmp") && isFullyProcessed(path))
                {
                    Files.delete(path);
                    checkpoints.remove(path);
                }
            }
        }
    }

    private void processFile(Path path) throws IOException
    {
        long offset = checkpoints.getOrDefault(path, 0L);

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r"))
        {
            long fileLength = raf.length();
            if (fileLength <= offset)
            {
                return;
            }

            MappedByteBuffer buffer = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, fileLength);
            buffer.position((int) offset);

            // Check version byte if starting from beginning
            if (offset == 0)
            {
                if (buffer.remaining() > 0)
                {
                    byte version = buffer.get();
                    if (version != Marker.VERSION)
                    {
                        logger.error("Unknown journal version: {} in {}", version, path);
                        // Decide how to handle version mismatch - skip file or try to process?
                        // For now, we'll just log and continue, assuming backward compatibility or manual intervention
                    }
                    checkpoints.put(path, (long) buffer.position());
                }
                else
                {
                    // File is empty, wait for data
                    return;
                }
            }

            while (buffer.remaining() > 0)
            {
                int startPos = buffer.position();
                byte marker = buffer.get();

                if (marker == 0)
                {
                    buffer.position(startPos);
                    break;
                }

                try
                {
                    parseEvent(marker, buffer);
                    checkpoints.put(path, (long) buffer.position());
                }
                catch (Exception e)
                {
                    // If parsing fails, we might be reading a partial write or corrupt data
                    // Reset position to start of this event and stop processing this file for now
                    buffer.position(startPos);
                    break;
                }
            }
        }
    }

    private void parseEvent(byte marker, MappedByteBuffer buffer) throws IOException
    {
        if (marker == Marker.BEGIN)
        {
            int dir = buffer.getInt();
            String reqId = readString(buffer);
            String startLine = readString(buffer);
            Map<String, String> headers = readHeaders(buffer);
            listener.onBegin(dir, reqId, startLine, headers);
        }
        else if (marker == Marker.BODY)
        {
            String reqId = readString(buffer);
            int bodyLen = buffer.getInt();
            if (bodyLen > 0)
            {
                // Create a read-only slice for the body content
                ByteBuffer body = buffer.slice();
                body.limit(bodyLen);
                listener.onBody(reqId, body);

                // Advance the main buffer past the body
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
            listener.onEnd(reqId, endTs, status, bytesSent, bytesReceived, durationNanos);
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

    private boolean isFullyProcessed(Path path) throws IOException
    {
        Long processed = checkpoints.get(path);
        return processed != null && processed >= Files.size(path);
    }
}