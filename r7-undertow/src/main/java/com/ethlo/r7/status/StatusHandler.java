package com.ethlo.r7.status;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.config.DefaultGatewayRoute;
import com.ethlo.r7.config.HotReloadService;
import com.ethlo.r7.config.RouteRegistry;
import com.ethlo.r7.journal.HeaderNameSet;
import com.ethlo.r7.journal.JournalSecurity;
import com.ethlo.r7.r7f.DiskSpaceUtils;
import com.ethlo.r7.status.dto.ModelMapper;
import com.ethlo.r7.status.dto.RouteConfigDto;
import com.ethlo.r7.undertow.R7UndertowHandler;
import com.ethlo.r7.undertow.config.ServerConfig;
import com.ethlo.r7.util.JsonUtil;
import com.ethlo.r7.util.SystemUtil;
import com.ethlo.r7.util.constants.MediaTypes;
import io.undertow.server.ConnectorStatistics;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;

public final class StatusHandler implements HttpHandler
{
    private final MetricsRegistry metricsRegistry;
    private final ServerConfig serverConfig;
    private final HeaderNameSet safeRequestHeaders;
    private final HeaderNameSet safeResponseHeaders;
    private final RouteRegistry routeRegistry;
    private final HotReloadService hotReloadService;
    private final R7UndertowHandler gatewayHandler;
    private final String serverConfigFile;
    private final String combinedHtml;
    private final String contentSecurityPolicy;
    private ConnectorStatistics connectorStatistics;

    /**
     * @param serverConfigFile the server.yaml that was loaded, or null when the defaults are in use
     */
    public StatusHandler(final MetricsRegistry metricsRegistry, final ServerConfig serverConfig, final String serverConfigFile, final RouteRegistry routeRegistry, final HotReloadService hotReloadService, final R7UndertowHandler gatewayHandler)
    {
        this.metricsRegistry = metricsRegistry;
        this.serverConfig = serverConfig;
        this.serverConfigFile = serverConfigFile;
        this.hotReloadService = hotReloadService;
        this.gatewayHandler = gatewayHandler;
        // The journal's effective whitelist, overrides included: a header value it would redact is
        // not shown here either.
        final ServerConfig.JournalSecurityConfig journalSecurity = serverConfig.storage().journalSecurity();
        this.safeRequestHeaders = JournalSecurity.resolveSafeRequestHeaders(
                journalSecurity.additionalSafeRequestHeaders(), journalSecurity.safeRequestHeaders());
        this.safeResponseHeaders = JournalSecurity.resolveSafeResponseHeaders(
                journalSecurity.additionalSafeResponseHeaders(), journalSecurity.safeResponseHeaders());
        this.routeRegistry = routeRegistry;
        this.combinedHtml = loadResource("page.html");
        this.contentSecurityPolicy = contentSecurityPolicy(this.combinedHtml);
    }

    /**
     * The page's one inline script is allowed by its hash, computed from the page as loaded, so
     * the policy cannot drift from the markup; any other script - injected or not - is refused.
     * Styles stay inline-permitted: CSS cannot run code, and the page styles its markup inline.
     */
    static String contentSecurityPolicy(final String html)
    {
        final int open = html.indexOf("<script>");
        final int close = html.indexOf("</script>", open);
        if (open < 0 || close < 0 || html.indexOf("<script", open + 1) >= 0)
        {
            throw new IllegalStateException("The dashboard must contain exactly one inline <script> block");
        }
        final String script = html.substring(open + "<script>".length(), close);
        final String hash;
        try
        {
            hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8)));
        }
        catch (final NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
        return "default-src 'none'; script-src 'sha256-" + hash + "'; style-src 'unsafe-inline'; img-src data:; connect-src 'self'; "
                + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
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

        // The dashboard shows gateway internals: never cache it, frame it, sniff it or leak its
        // URL to other origins.
        exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-store");
        exchange.getResponseHeaders().put(Headers.X_CONTENT_TYPE_OPTIONS, "nosniff");
        exchange.getResponseHeaders().put(Headers.X_FRAME_OPTIONS, "DENY");
        exchange.getResponseHeaders().put(Headers.REFERRER_POLICY, "no-referrer");
        exchange.getResponseHeaders().put(Headers.CONTENT_SECURITY_POLICY, this.contentSecurityPolicy);

        // Read-only: nothing here changes state, so nothing but a read is accepted.
        if (!Methods.GET.equals(exchange.getRequestMethod()) && !Methods.HEAD.equals(exchange.getRequestMethod()))
        {
            exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
            exchange.getResponseHeaders().put(Headers.ALLOW, "GET, HEAD");
            exchange.endExchange();
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

        final Map<String, Object> system = new LinkedHashMap<>();
        system.put("version", VersionProvider.getVersion());
        system.put("uptime", SystemUtil.getUptime());
        system.put("started_at", SystemUtil.getStartTime());
        system.put("configuration", serverConfig);
        system.put("configuration_defaults", ServerConfig.standard());
        system.put("configuration_file", serverConfigFile);
        system.put("memory", SystemMetricsCollector.collect());
        root.put("system", system);
        root.put("connector_statistics", ModelMapper.from(connectorStatistics));
        root.put("journaling", Map.of("available_space", DiskSpaceUtils.getSafeUsableSpace(Paths.get(serverConfig.storage().workDir()))));
        root.put("route_metrics", metricsRegistry.getAll());

        root.put("unrouted_requests", gatewayHandler.unroutedRequests());
        root.put("upstream_health", gatewayHandler.upstreamTargetStates());

        final List<GatewayRoute> routes = routeRegistry.getRoutes();
        final List<RouteConfigDto> routeConfigs = new ArrayList<>(routes.size());
        for (int i = 0; i < routes.size(); i++)
        {
            routeConfigs.add(ModelMapper.mapRouteConfig((DefaultGatewayRoute) routes.get(i), i + 1, this.safeRequestHeaders, this.safeResponseHeaders));
        }
        root.put("route_version", routeRegistry.getConfigVersion());
        root.put("route_source", hotReloadService.status());
        root.put("route_configs", routeConfigs);

        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, MediaTypes.APPLICATION_JSON);
        exchange.getResponseSender().send(JsonUtil.writeValueAsString(root));
    }

    private void serveHtml(final HttpServerExchange exchange)
    {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, MediaTypes.TEXT_HTML + "; charset=utf-8");
        exchange.getResponseSender().send(combinedHtml);
    }

    public void setConnectorStatistics(ConnectorStatistics connectorStatistics)
    {
        this.connectorStatistics = connectorStatistics;
    }
}