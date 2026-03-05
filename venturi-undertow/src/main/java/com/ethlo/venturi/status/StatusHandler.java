package com.ethlo.venturi.status;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ethlo.venturi.config.RouteDefinition;
import com.ethlo.venturi.status.dto.EgressDto;
import com.ethlo.venturi.status.dto.FilterDto;
import com.ethlo.venturi.status.dto.IngressDto;
import com.ethlo.venturi.status.dto.JournalDto;
import com.ethlo.venturi.status.dto.MatchDto;
import com.ethlo.venturi.status.dto.PerformanceTelemetryDto;
import com.ethlo.venturi.status.dto.RequestStatsDto;
import com.ethlo.venturi.status.dto.RouteConfigDto;
import com.ethlo.venturi.status.dto.RouteMetricsDto;
import com.ethlo.venturi.status.dto.TrafficFlowDto;
import com.ethlo.venturi.undertow.config.ServerConfig;
import com.ethlo.venturi.util.SystemUtil;
import com.ethlo.venturi.util.constants.MediaTypes;
import com.ethlo.venturi.vlf.DiskSpaceUtils;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import tools.jackson.databind.ObjectMapper;

public final class StatusHandler implements HttpHandler
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MetricsRegistry registry;
    private final ServerConfig serverConfig;
    private final String combinedHtml;

    public StatusHandler(final MetricsRegistry registry, ServerConfig serverConfig)
    {
        this.registry = registry;
        this.serverConfig = serverConfig;
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
    public void handleRequest(final HttpServerExchange exchange) throws IOException
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

    private RouteConfigDto mapToDto(final RouteDefinition def)
    {
        final MatchDto match = new MatchDto(
                def.match().getClass().getSimpleName(),
                def.match().toString()
        );

        final JournalDto journal = new JournalDto(
                def.journal().request().level().name(),
                def.journal().response().level().name()
        );

        final List<FilterDto> filters = def.filters().stream()
                .map(f -> new FilterDto(f.name(), f.args()))
                .toList();

        return new RouteConfigDto(
                def.id().toString(),
                match,
                journal,
                def.upstream().targets().toString(),
                filters
        );
    }

    private RouteMetricsDto mapToDto(final Map.Entry<String, SimpleMetricsFactory.GF> gfEntry)
    {
        final SimpleMetricsFactory.GF gf = gfEntry.getValue();
        final IngressDto ingress = new IngressDto(
                gf.getTotalRequestHeaderBytes(),
                gf.getTotalRequestBodyBytes(),
                gf.getTotalRequestHeaderBytes() + gf.getTotalRequestBodyBytes()
        );

        final EgressDto egress = new EgressDto(
                gf.getTotalResponseHeaderBytes(),
                gf.getTotalResponseBodyBytes(),
                gf.getTotalResponseHeaderBytes() + gf.getTotalResponseBodyBytes()
        );

        final TrafficFlowDto traffic = new TrafficFlowDto(
                ingress,
                egress,
                gf.getTotalJournalBytes()
        );

        final PerformanceTelemetryDto performance = new PerformanceTelemetryDto(
                gf.getAvgLatencyNanos()
        );

        final RequestStatsDto stats = new RequestStatsDto(gf.getTotalRequests(), gf.getActiveRequests(),
                gf.getStatus2xxRequests(), gf.getStatus3xxRequests(), gf.getStatus4xxRequests(), gf.getStatus5xxRequests(),
                gf.getUpstreamRequests());

        return new RouteMetricsDto(gfEntry.getKey(), stats, traffic, performance);
    }

    private void serveJson(final HttpServerExchange exchange)
    {
        final Map<String, Object> root = new LinkedHashMap<>();

        root.put("system", Map.of(
                        "uptime", SystemUtil.getUptime(),
                        "started_at", SystemUtil.getStartTime()
                )
        );

        root.put("journaling", Map.of("available_space", DiskSpaceUtils.getSafeUsableSpace(Paths.get(serverConfig.storage().workDir()))));

        root.put("route_metrics", registry.getAll().entrySet().stream().map(this::mapToDto).toList());

        final List<RouteConfigDto> manifest = registry.getRouteDefinitions().stream()
                .map(this::mapToDto)
                .toList();
        root.put("route_configs", manifest);

        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, MediaTypes.APPLICATION_JSON);
        exchange.getResponseSender().send(MAPPER.writeValueAsString(root));
    }

    private void serveHtml(final HttpServerExchange exchange)
    {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, MediaTypes.TEXT_HTML);
        exchange.getResponseSender().send(combinedHtml);
    }
}