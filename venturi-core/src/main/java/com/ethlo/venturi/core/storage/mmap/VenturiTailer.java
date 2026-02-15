package com.ethlo.venturi.core.storage.mmap;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.ethlo.venturi.core.ServerDirection;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.json.JsonFactory;

public class VenturiTailer
{
    private final Map<Path, Long> checkpoints = new HashMap<>();
    private final Path logDir = Paths.get("/tmp/venturi");
    private final Path outputDir = Paths.get("/tmp/venturi-ingest");
    private final Map<String, PendingRequest> pending = new HashMap<>();
    private final JsonFactory jsonFactory = new JsonFactory();

    public static void main(String[] args) throws InterruptedException
    {
        VenturiTailer tailer = new VenturiTailer();
        System.out.println("Venturi Tailer started. Watching /tmp/venturi...");

        while (!Thread.currentThread().isInterrupted())
        {
            try
            {
                tailer.runTick();
            }
            catch (Exception e)
            {
                System.err.println("Error during tailer tick: " + e.getMessage());
                e.printStackTrace();
            }

            // 1-second heartbeat to keep latency low in low-traffic periods
            Thread.sleep(1000);
        }
    }

    public void runTick() throws IOException
    {
        if (!Files.exists(outputDir))
        {
            Files.createDirectories(outputDir);
        }

        cleanupStaleRequests();

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
            if (fileLength <= offset) return;

            MappedByteBuffer buffer = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, fileLength);
            buffer.position((int) offset);

            Path outPath = outputDir.resolve(path.getFileName().toString() + ".json");

            // Use BufferedWriter + auto-flush for better performance with Jackson strings
            try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(outPath.toFile(), true)), true))
            {
                while (buffer.remaining() > 0)
                {
                    int startPos = buffer.position();
                    byte marker = buffer.get();

                    if (marker == 0)
                    {
                        buffer.position(startPos);
                        break;
                    }

                    String json = tryParseToJson(marker, buffer);
                    if (json != null)
                    {
                        writer.println(json);
                    }

                    checkpoints.put(path, (long) buffer.position());
                }
            }
        }
    }

    private String tryParseToJson(byte marker, MappedByteBuffer buffer) throws IOException
    {
        if (marker == Marker.BEGIN)
        {
            int dir = buffer.getInt();
            String reqId = readString(buffer);
            String startLine = readString(buffer);
            Map<String, String> headers = readHeaders(buffer);

            PendingRequest req = pending.computeIfAbsent(reqId, id -> new PendingRequest());
            if (dir == ServerDirection.REQUEST.ordinal())
            {
                req.methodPath = startLine;
                req.reqHeaders = headers;
            }
            else
            {
                req.statusLine = startLine;
                req.resHeaders = headers;
            }
            return null;
        }
        else if (marker == Marker.BODY)
        {
            // CRITICAL: We must skip the body data to reach the END marker
            readString(buffer); // Skip reqId
            int bodyLen = buffer.getInt();
            if (bodyLen > 0)
            {
                buffer.position(buffer.position() + bodyLen);
            }
        }
        else if (marker == Marker.END)
        {
            String reqId = readString(buffer);
            long endTs = buffer.getLong();

            PendingRequest req = pending.remove(reqId);
            if (req != null)
            {
                return buildFinalJson(reqId, req, endTs);
            }
        }
        return null;
    }

    private String buildFinalJson(String id, PendingRequest req, long endTs) throws IOException
    {
        StringWriter sw = new StringWriter();
        try (JsonGenerator jg = jsonFactory.createGenerator(sw))
        {
            jg.writeStartObject();
            jg.writeStringProperty("id", id);
            jg.writeNumberProperty("ts", endTs);
            jg.writeStringProperty("request", req.methodPath);
            jg.writeStringProperty("status", req.statusLine);

            writeHeaderMap(jg, "req_headers", req.reqHeaders);
            writeHeaderMap(jg, "res_headers", req.resHeaders);

            jg.writeEndObject();
        }
        return sw.toString();
    }

    private void writeHeaderMap(JsonGenerator jg, String fieldName, Map<String, String> headers) throws IOException
    {
        jg.writeObjectPropertyStart(fieldName);
        if (headers != null)
        {
            for (Map.Entry<String, String> entry : headers.entrySet())
            {
                jg.writeStringProperty(entry.getKey(), entry.getValue());
            }
        }
        jg.writeEndObject();
    }

    private Map<String, String> readHeaders(ByteBuffer buffer)
    {
        int count = buffer.getInt();
        if (count <= 0) return Collections.emptyMap();
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
        if (len <= 0) return len == 0 ? "" : null;
        byte[] bytes = new byte[len];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private boolean isFullyProcessed(Path path) throws IOException
    {
        Long processed = checkpoints.get(path);
        return processed != null && processed >= Files.size(path);
    }

    private void cleanupStaleRequests()
    {
        long now = System.currentTimeMillis();
        long threshold = TimeUnit.MINUTES.toMillis(5);
        pending.entrySet().removeIf(e -> (now - e.getValue().createdAt) > threshold);
    }

    public static class PendingRequest
    {
        public final long createdAt = System.currentTimeMillis();
        public String methodPath;
        public Map<String, String> reqHeaders;
        public String statusLine;
        public Map<String, String> resHeaders;
    }
}