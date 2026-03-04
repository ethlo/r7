package com.ethlo.venturi.metrics.filters;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

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
        final StringBuilder json = new StringBuilder("{\"routes\":[");
        final Map<String, SimpleMetricsFactory.GF> metrics = registry.getAll();

        metrics.forEach((id, gf) -> {
            json.append(String.format(
                    "{\"id\":\"%s\",\"total\":%d,\"active\":%d,\"upstream\":%d,\"avg_latency_ns\":%d," +
                            "\"bytes_in\":%d,\"bytes_out\":%d,\"journal_bytes\":%d},",
                    id, gf.getTotalRequests(), gf.getActiveRequests(), gf.getUpstreamRequests(), gf.getAvgLatencyNanos(),
                    gf.getTotalBytesIn(), gf.getTotalBytesOut(), -1
            )); //gf.getBytesIn(), gf.getBytesOut(), gf.getJournalBytes()

        });

        if (!metrics.isEmpty()) json.setLength(json.length() - 1);
        json.append("]}");

        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, MediaTypes.APPLICATION_JSON);
        exchange.getResponseSender().send(json.toString());
    }

    private void serveHtml(final HttpServerExchange exchange)
    {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, MediaTypes.TEXT_HTML);
        exchange.getResponseSender().send(combinedHtml);
    }
}