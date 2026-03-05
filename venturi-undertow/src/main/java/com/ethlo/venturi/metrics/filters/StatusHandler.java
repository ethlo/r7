package com.ethlo.venturi.metrics.filters;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import com.ethlo.venturi.util.SystemUtil;
import com.ethlo.venturi.util.constants.MediaTypes;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

public final class StatusHandler implements HttpHandler
{
    private final MetricsRegistry registry;
    private final String combinedHtml;

    public StatusHandler(final MetricsRegistry registry)
    {
        this.registry = registry;
        this.combinedHtml = loadResource("page.html")
                .replace("<link rel=\"stylesheet\" href=\"style.css\">", "<style>" + loadResource("style.css") + "</style>")
                .replace("<script src=\"script.js\"></script>", "<script>" + loadResource("script.js") + "</script>");
    }

    private String loadResource(final String path)
    {
        final String fullPath = "/dashboard/default/" + path;
        try (final InputStream stream = getClass().getResourceAsStream(fullPath))
        {
            if (stream == null)
            {
                throw new FileNotFoundException("Resource not found: " + fullPath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange)
    {
        if (exchange.isInIoThread())
        {
            exchange.dispatch(this);
            return;
        }

        final String accept = exchange.getRequestHeaders().getFirst(Headers.ACCEPT);
        if (accept != null && accept.contains("application/json"))
        {
            serveJson(exchange);
        }
        else
        {
            serveHtml(exchange);
        }
    }

    private void serveJson(final HttpServerExchange exchange)
    {
        final StringBuilder json = new StringBuilder("{");

        // 1. System Vitals
        json.append(String.format("\"system\":{\"uptime_ms\":%d,\"start_ts\":\"%s\"},",
                SystemUtil.getUptime(), getStartTime()
        ));

        // 2. Route Metrics with Granular Byte Accounting
        json.append("\"routes\":[");
        final Map<String, SimpleMetricsFactory.GF> metrics = registry.getAll();
        metrics.forEach((id, gf) -> {
            json.append(String.format(
                    "{\"id\":\"%s\",\"total\":%d,\"active\":%d,\"upstream\":%d,\"avg_latency_ns\":%d," +
                            "\"h_in\":%d,\"b_in\":%d,\"h_out\":%d,\"b_out\":%d,\"journal_bytes\":%d},",
                    id, gf.getTotalRequests(), gf.getActiveRequests(), gf.getUpstreamRequests(), gf.getAvgLatencyNanos(),
                    gf.getTotalRequestHeaderBytes(), gf.getTotalRequestBodyBytes(),
                    gf.getTotalResponseHeaderBytes(), gf.getTotalResponseBodyBytes(),
                    gf.getTotalJournalBytes()
            ));
        });
        if (!metrics.isEmpty()) json.setLength(json.length() - 1);
        json.append("],");

        // 3. Static Configs remains unchanged
        json.append("\"configs\":{");
        registry.getRouteDefinitions().forEach((def) -> {
            json.append(String.format("\"%s\":{\"upstream\":\"%s\",\"filters\":\"%s\",\"journal\":\"%s\"},",
                    def.id(), escape(def.upstream()), escape(def.filters()), escape(def.journal())
            ));
        });
        if (!registry.getRouteDefinitions().isEmpty()) json.setLength(json.length() - 1);
        json.append("}}");

        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, MediaTypes.APPLICATION_JSON);
        exchange.getResponseSender().send(json.toString());
    }

    public OffsetDateTime getStartTime()
    {
        final long startupTime = SystemUtil.getUptime();
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli((System.nanoTime() - startupTime) / 1000), ZoneOffset.UTC);
    }

    private String escape(Object object)
    {
        return object.toString();
    }

    private void serveHtml(final HttpServerExchange exchange)
    {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, MediaTypes.TEXT_HTML);
        exchange.getResponseSender().send(combinedHtml);
    }
}