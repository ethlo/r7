package com.ethlo.r7.status;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.config.DefaultGatewayRoute;
import com.ethlo.r7.config.HotReloadService;
import com.ethlo.r7.config.RouteRegistry;
import com.ethlo.r7.journal.HeaderNameSet;
import com.ethlo.r7.journal.JournalSecurity;
import com.ethlo.r7.r7f.DiskSpaceUtils;
import com.ethlo.r7.server.GatewayPipeline;
import com.ethlo.r7.server.config.ServerConfig;
import com.ethlo.r7.status.dto.ConnectorStatisticsDto;
import com.ethlo.r7.status.dto.ModelMapper;
import com.ethlo.r7.status.dto.RouteConfigDto;
import com.ethlo.r7.util.JsonUtil;
import com.ethlo.r7.util.SystemUtil;
import com.ethlo.r7.util.constants.MediaTypes;

/**
 * The management port's one resource: the dashboard page, or its data as JSON. Server-neutral -
 * a server hands it the method, Host and Accept of a request and writes back the
 * {@link Response} - so every r7 server answers the same, security headers included.
 */
public final class ManagementEndpoint
{
    private final MetricsRegistry metricsRegistry;
    private final ServerConfig serverConfig;
    private final HeaderNameSet safeRequestHeaders;
    private final HeaderNameSet safeResponseHeaders;
    private final RouteRegistry routeRegistry;
    private final HotReloadService hotReloadService;
    private final GatewayPipeline pipeline;
    private final String serverConfigFile;
    private final String combinedHtml;
    private final Map<String, String> securityHeaders;
    private final ManagementHostPolicy hostPolicy;
    private final Supplier<ConnectorStatisticsDto> connectorStatistics;

    /**
     * An answer to write: status, headers in order, and a body (empty for none).
     */
    public record Response(int status, Map<String, String> headers, byte[] body)
    {
    }

    /**
     * @param serverConfigFile    the server.yaml that was loaded, or null when the defaults are in use
     * @param connectorStatistics the server's listener counters, or null where it keeps none
     */
    public ManagementEndpoint(final MetricsRegistry metricsRegistry, final ServerConfig serverConfig, final String serverConfigFile, final RouteRegistry routeRegistry,
                              final HotReloadService hotReloadService, final GatewayPipeline pipeline, final Supplier<ConnectorStatisticsDto> connectorStatistics)
    {
        this.metricsRegistry = metricsRegistry;
        this.serverConfig = serverConfig;
        this.serverConfigFile = serverConfigFile;
        this.hotReloadService = hotReloadService;
        this.pipeline = pipeline;
        this.connectorStatistics = connectorStatistics;
        // The journal's effective whitelist, overrides included: a header value it would redact is
        // not shown here either.
        final ServerConfig.JournalSecurityConfig journalSecurity = serverConfig.storage().journalSecurity();
        this.safeRequestHeaders = JournalSecurity.resolveSafeRequestHeaders(
                journalSecurity.additionalSafeRequestHeaders(), journalSecurity.safeRequestHeaders());
        this.safeResponseHeaders = JournalSecurity.resolveSafeResponseHeaders(
                journalSecurity.additionalSafeResponseHeaders(), journalSecurity.safeResponseHeaders());
        this.routeRegistry = routeRegistry;
        this.combinedHtml = loadResource("page.html");
        this.hostPolicy = new ManagementHostPolicy(serverConfig.management().host(), serverConfig.management().allowedHosts());

        // The dashboard shows gateway internals: never cache it, frame it, sniff it or leak its
        // URL to other origins.
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cache-Control", "no-store");
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("X-Frame-Options", "DENY");
        headers.put("Referrer-Policy", "no-referrer");
        headers.put("Content-Security-Policy", contentSecurityPolicy(this.combinedHtml));
        this.securityHeaders = Map.copyOf(headers);
    }

    /**
     * The page's one inline script is allowed by its hash, computed from the page as loaded, so
     * the policy cannot drift from the markup; any other script - injected or not - is refused.
     * Styles stay inline-permitted: CSS cannot run code, and the page styles its markup inline.
     */
    public static String contentSecurityPolicy(final String html)
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

    /**
     * Answers one request. Blocks briefly (disk space, metrics), so it must run where blocking is
     * allowed.
     *
     * @param method the request method
     * @param host   the Host header, or null
     * @param accept the Accept header, or null
     */
    public Response handle(final String method, final String host, final String accept)
    {
        final Map<String, String> headers = new LinkedHashMap<>(this.securityHeaders);

        // A name the operator did not configure is what DNS rebinding looks like from here
        if (!this.hostPolicy.allows(host))
        {
            // 421 Misdirected Request: this server is not configured to answer for that name
            return new Response(421, headers, new byte[0]);
        }

        // Read-only: nothing here changes state, so nothing but a read is accepted.
        if (!"GET".equals(method) && !"HEAD".equals(method))
        {
            headers.put("Allow", "GET, HEAD");
            return new Response(405, headers, new byte[0]);
        }

        if (accept != null && accept.contains("application/json"))
        {
            headers.put("Content-Type", MediaTypes.APPLICATION_JSON);
            return new Response(200, headers, JsonUtil.writeValueAsString(json()).getBytes(StandardCharsets.UTF_8));
        }
        headers.put("Content-Type", MediaTypes.TEXT_HTML + "; charset=utf-8");
        return new Response(200, headers, this.combinedHtml.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> json()
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
        root.put("connector_statistics", connectorStatistics != null ? connectorStatistics.get() : null);
        root.put("journaling", Map.of("available_space", DiskSpaceUtils.getSafeUsableSpace(Paths.get(serverConfig.storage().workDir()))));
        root.put("route_metrics", metricsRegistry.getAll());

        root.put("unrouted_requests", pipeline.unroutedRequests());
        root.put("upstream_health", pipeline.upstreamTargetStates());

        final List<GatewayRoute> routes = routeRegistry.getRoutes();
        final List<RouteConfigDto> routeConfigs = new ArrayList<>(routes.size());
        for (int i = 0; i < routes.size(); i++)
        {
            routeConfigs.add(ModelMapper.mapRouteConfig((DefaultGatewayRoute) routes.get(i), i + 1, this.safeRequestHeaders, this.safeResponseHeaders));
        }
        root.put("route_version", routeRegistry.getConfigVersion());
        root.put("route_source", hotReloadService.status());
        root.put("route_configs", routeConfigs);
        return root;
    }

    private String loadResource(final String... paths)
    {
        final StringBuilder sb = new StringBuilder();
        for (final String p : paths)
        {
            final String fullPath = "/dashboard/default/" + p;
            try (final InputStream stream = ManagementEndpoint.class.getResourceAsStream(fullPath))
            {
                if (stream == null)
                {
                    throw new FileNotFoundException("Resource not found: " + fullPath);
                }
                sb.append(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
            catch (final IOException e)
            {
                throw new UncheckedIOException(e);
            }
        }
        return sb.toString();
    }
}
