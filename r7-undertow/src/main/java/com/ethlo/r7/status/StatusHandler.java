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

import com.ethlo.r7.config.DefaultGatewayRoute;
import com.ethlo.r7.config.RouteRegistry;
import com.ethlo.r7.status.dto.ModelMapper;
import com.ethlo.r7.status.dto.RouteConfigDto;
import com.ethlo.r7.undertow.config.ServerConfig;
import com.ethlo.r7.util.JsonUtil;
import com.ethlo.r7.util.SystemUtil;
import com.ethlo.r7.util.constants.MediaTypes;
import com.ethlo.r7.r7f.DiskSpaceUtils;
import io.undertow.server.ConnectorStatistics;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

public final class StatusHandler implements HttpHandler
{
    private final MetricsRegistry metricsRegistry;
    private final ServerConfig serverConfig;
    private final RouteRegistry routeRegistry;
    private final String combinedHtml;
    private ConnectorStatistics connectorStatistics;

    public StatusHandler(final MetricsRegistry metricsRegistry, ServerConfig serverConfig, RouteRegistry routeRegistry)
    {
        this.metricsRegistry = metricsRegistry;
        this.serverConfig = serverConfig;
        this.routeRegistry = routeRegistry;
        this.combinedHtml = loadResource("page.html");
    }

    private String loadResource(final String... paths)
    {
        final StringBuilder sb = new StringBuilder();
        for (String p : paths)
        {
            final String fullPath = "/dashboard/default/" + p;
            try (final InputStream stream = getClass().getResourceAsStream(fullPath))
            {
                if (stream == null)
                {
                    throw new FileNotFoundException("Resource not found: " + fullPath);
                }
                sb.append(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(e);
            }
        }
        return sb.toString();
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
        final Map<String, Object> root = new LinkedHashMap<>();

        root.put("system", Map.of(
                        "version", VersionProvider.getVersion(),
                        "uptime", SystemUtil.getUptime(),
                        "started_at", SystemUtil.getStartTime(),
                        "configuration", serverConfig,
                        "memory", SystemMetricsCollector.collect()
                )
        );
        root.put("connector_statistics", ModelMapper.from(connectorStatistics));
        root.put("journaling", Map.of("available_space", DiskSpaceUtils.getSafeUsableSpace(Paths.get(serverConfig.storage().workDir()))));
        root.put("route_metrics", metricsRegistry.getAll());

        final List<RouteConfigDto> routeConfigs = routeRegistry.getRoutes().stream()
                .map(DefaultGatewayRoute.class::cast)
                .map(ModelMapper::mapRouteConfig)
                .toList();
        root.put("route_version", routeRegistry.getConfigVersion());
        root.put("route_configs", routeConfigs);

        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, MediaTypes.APPLICATION_JSON);
        exchange.getResponseSender().send(JsonUtil.writeValueAsString(root));
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