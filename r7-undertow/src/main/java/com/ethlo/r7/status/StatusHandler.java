package com.ethlo.r7.status;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ethlo.r7.status.dto.ModelMapper;
import com.ethlo.r7.status.dto.RouteConfigDto;
import com.ethlo.r7.undertow.config.ServerConfig;
import com.ethlo.r7.util.SystemUtil;
import com.ethlo.r7.util.constants.MediaTypes;
import com.ethlo.r7.vlf.DiskSpaceUtils;
import io.undertow.server.ConnectorStatistics;
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
    private ConnectorStatistics connectorStatistics;

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

    private void serveJson(final HttpServerExchange exchange)
    {
        final Map<String, Object> root = new LinkedHashMap<>();

        root.put("system", Map.of(
                        "version", VersionProvider.getVersion(),
                        "uptime", SystemUtil.getUptime(),
                        "started_at", SystemUtil.getStartTime()
                )
        );
        root.put("connector_statistics", ModelMapper.from(connectorStatistics));
        root.put("journaling", Map.of("available_space", DiskSpaceUtils.getSafeUsableSpace(Paths.get(serverConfig.storage().workDir()))));
        root.put("route_metrics", registry.getAll().entrySet().stream().map(ModelMapper::mapToDto).toList());

        final List<RouteConfigDto> manifest = registry.getRouteDefinitions().stream()
                .map(ModelMapper::mapToDto)
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

    public void setConnectorStatistics(ConnectorStatistics connectorStatistics)
    {
        this.connectorStatistics = connectorStatistics;
    }
}