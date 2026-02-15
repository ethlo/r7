package com.ethlo.venturi.core.storage.mmap;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.ethlo.venturi.core.ServerDirection;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.json.JsonFactory;

public class JsonFileJournalEventListener implements JournalEventListener
{
    private final Path outputDir;
    private final Map<String, PendingRequest> pending = new HashMap<>();
    private final JsonFactory jsonFactory = new JsonFactory();

    public JsonFileJournalEventListener(Path outputDir)
    {
        this.outputDir = outputDir;
        if (!Files.exists(outputDir))
        {
            try
            {
                Files.createDirectories(outputDir);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void onBegin(int direction, String reqId, String startLine, Map<String, String> headers)
    {
        PendingRequest req = pending.computeIfAbsent(reqId, id -> new PendingRequest());
        if (direction == ServerDirection.REQUEST.ordinal())
        {
            req.methodPath = startLine;
            req.reqHeaders = headers;
        }
        else
        {
            req.statusLine = startLine;
            req.resHeaders = headers;
        }
    }

    @Override
    public void onBody(String reqId, ByteBuffer body)
    {
        // For now, we just skip the body in the JSON output as per original implementation
    }

    @Override
    public void onEnd(String reqId, long timestamp, int status, long bytesSent, long bytesReceived, long durationNanos)
    {
        PendingRequest req = pending.remove(reqId);
        if (req != null)
        {
            try
            {
                String json = buildFinalJson(reqId, req, timestamp, status, bytesSent, bytesReceived, durationNanos);
                Path outPath = outputDir.resolve("access.log"); // Or per-request file if needed
                try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(outPath.toFile(), true)), true))
                {
                    writer.println(json);
                }
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    private String buildFinalJson(String id, PendingRequest req, long endTs, int status, long bytesSent, long bytesReceived, long durationNanos) throws IOException
    {
        StringWriter sw = new StringWriter();
        try (JsonGenerator jg = jsonFactory.createGenerator(sw))
        {
            jg.writeStartObject();
            jg.writeStringProperty("id", id);
            jg.writeNumberProperty("ts", endTs);
            jg.writeStringProperty("request", req.methodPath);
            jg.writeStringProperty("status_line", req.statusLine);

            // Access Log Fields
            jg.writeNumberProperty("status", status);
            jg.writeNumberProperty("bytes_sent", bytesSent);
            jg.writeNumberProperty("bytes_received", bytesReceived);
            // Use floating point for sub-millisecond precision
            jg.writeNumberProperty("duration_ms", durationNanos / 1_000_000.0);

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

    public static class PendingRequest
    {
        public final long createdAt = System.currentTimeMillis();
        public String methodPath;
        public Map<String, String> reqHeaders;
        public String statusLine;
        public Map<String, String> resHeaders;
    }
}