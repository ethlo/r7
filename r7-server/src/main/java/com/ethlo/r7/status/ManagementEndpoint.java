package com.ethlo.r7.status;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.api.ComponentStatus;
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
import com.ethlo.r7.status.dto.FilterNode;
import com.ethlo.r7.status.dto.MemoryDto;
import com.ethlo.r7.status.dto.ModelMapper;
import com.ethlo.r7.status.dto.RouteConfigDto;
import com.ethlo.r7.util.JsonUtil;
import com.ethlo.r7.util.SystemUtil;
import com.ethlo.r7.util.constants.MediaTypes;

/**
 * The management port: {@code /metrics} in the Prometheus text format, {@code /health}, and on
 * every other path the dashboard page, or its data as JSON. Server-neutral - a server hands it
 * the method, path, Host and Accept of a request and writes back the {@link Response} - so every
 * r7 server answers the same, security headers included.
 * <p>
 * The JSON and the metrics are rendered together every {@link #SNAPSHOT_INTERVAL} on the
 * gateway's scheduler, and a request is answered with those bytes as they stand. Nothing is
 * computed per request, so any number of dashboards and scrapers cost the gateway one rendering
 * per interval, and both read the same moment.
 */
public final class ManagementEndpoint
{
    static final Duration SNAPSHOT_INTERVAL = Duration.ofSeconds(2);

    /**
     * {@code /health} fails once the snapshot is older than this: the scheduler that renders it
     * also runs upstream health checks and configuration reloads, so a snapshot that stops being
     * renewed says the gateway's housekeeping has stalled.
     */
    static final Duration STALE_AFTER = Duration.ofSeconds(10);

    static final String METRICS_PATH = "/metrics";
    static final String HEALTH_PATH = "/health";

    private static final Logger logger = LoggerFactory.getLogger(ManagementEndpoint.class);
    private static final byte[] HEALTH_UP = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HEALTH_DOWN = "{\"status\":\"DOWN\"}".getBytes(StandardCharsets.UTF_8);

    /**
     * Histogram bounds, in microseconds: every power of two from 256µs to about 16.8s. Each is
     * the exact upper bound of a {@link LatencyHistogram} bucket, so a bucket is never split
     * between two of them.
     */
    private static final long[] LATENCY_BOUNDS_MICROS = latencyBounds();

    private record Snapshot(byte[] json, byte[] metrics, long renderedAtNanos)
    {
    }

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
    private final ScheduledFuture<?> rendering;
    private volatile Snapshot snapshot;

    /**
     * An answer to write: status, headers in order, and a body (empty for none).
     */
    public record Response(int status, Map<String, String> headers, byte[] body)
    {
    }

    /**
     * @param serverConfigFile    the server.yaml that was loaded, or null when the defaults are in use
     * @param connectorStatistics the server's listener counters, or null where it keeps none
     * @param scheduler           renders the snapshot every {@link #SNAPSHOT_INTERVAL}
     */
    public ManagementEndpoint(final MetricsRegistry metricsRegistry, final ServerConfig serverConfig, final String serverConfigFile, final RouteRegistry routeRegistry,
                              final HotReloadService hotReloadService, final GatewayPipeline pipeline, final Supplier<ConnectorStatisticsDto> connectorStatistics,
                              final GatewayScheduler scheduler)
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

        // The first one now, so the port has data to serve from the moment the gateway is up
        render();
        this.rendering = scheduler.scheduleEvery(SNAPSHOT_INTERVAL, this::render);
    }

    /**
     * Stops rendering snapshots.
     */
    public void close()
    {
        this.rendering.cancel(false);
    }

    /**
     * Renders the JSON and the metrics. A failure keeps the previous snapshot, which ages until
     * {@code /health} reports it.
     */
    void render()
    {
        try
        {
            final Map<String, Object> json = json();
            final byte[] metrics = metrics(json);
            this.snapshot = new Snapshot(JsonUtil.writeValueAsString(json).getBytes(StandardCharsets.UTF_8), metrics, System.nanoTime());
        }
        catch (final RuntimeException e)
        {
            logger.warn("Could not render the management snapshot", e);
        }
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
     * Answers one request from the latest snapshot; nothing is computed here.
     *
     * @param method the request method
     * @param path   the request path, without its query
     * @param host   the Host header, or null
     * @param accept the Accept header, or null
     */
    public Response handle(final String method, final String path, final String host, final String accept)
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

        final Snapshot current = this.snapshot;
        if (HEALTH_PATH.equals(path))
        {
            headers.put("Content-Type", MediaTypes.APPLICATION_JSON);
            final boolean fresh = current != null && System.nanoTime() - current.renderedAtNanos() <= STALE_AFTER.toNanos();
            return new Response(fresh ? 200 : 503, headers, fresh ? HEALTH_UP : HEALTH_DOWN);
        }

        final boolean metrics = METRICS_PATH.equals(path);
        if (metrics || (accept != null && accept.contains("application/json")))
        {
            if (current == null)
            {
                // Only until the first rendering succeeds
                return new Response(503, headers, new byte[0]);
            }
            headers.put("Content-Type", metrics ? PrometheusText.CONTENT_TYPE : MediaTypes.APPLICATION_JSON);
            return new Response(200, headers, metrics ? current.metrics() : current.json());
        }
        headers.put("Content-Type", MediaTypes.TEXT_HTML + "; charset=utf-8");
        return new Response(200, headers, this.combinedHtml.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> json()
    {
        final Map<String, Object> root = new LinkedHashMap<>();
        // Taken before anything is read, so everything below is at least this recent
        root.put("rendered_at", Instant.now());

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
        root.put("route_metrics", metricsRegistry.snapshot());

        root.put("unrouted_requests", pipeline.unroutedRequests());
        root.put("upstream_health", pipeline.upstreamTargetStates());

        final List<GatewayRoute> routes = routeRegistry.getRoutes();
        final List<RouteConfigDto> routeConfigs = new ArrayList<>(routes.size());
        for (int i = 0; i < routes.size(); i++)
        {
            final GatewayRoute route = routes.get(i);
            routeConfigs.add(ModelMapper.mapRouteConfig((DefaultGatewayRoute) route, i + 1, pipeline.upstreamStatus(route), this.safeRequestHeaders, this.safeResponseHeaders));
        }
        root.put("route_version", routeRegistry.getConfigVersion());
        root.put("route_source", hotReloadService.status());
        root.put("route_configs", routeConfigs);
        return root;
    }

    /**
     * The Prometheus metrics, from the same reading as the JSON wherever both show a value. The
     * metric names are documented in docs/config.md: a rename breaks someone's dashboard.
     */
    @SuppressWarnings("unchecked")
    private byte[] metrics(final Map<String, Object> json)
    {
        final PrometheusText out = new PrometheusText();

        out.metric("r7_info", "gauge", "The running r7, by version.")
                .sample("r7_info", 1, "version", String.valueOf(VersionProvider.getVersion()));
        out.metric("r7_start_time_seconds", "gauge", "When the process started, in seconds since the epoch.")
                .sample("r7_start_time_seconds", Instant.from(SystemUtil.getStartTime()).getEpochSecond());

        final HotReloadService.Status routeSource = (HotReloadService.Status) json.get("route_source");
        final boolean rejected = routeSource.rejectedAt() != null && (routeSource.loadedAt() == null || routeSource.rejectedAt().isAfter(routeSource.loadedAt()));
        out.metric("r7_routes_config_rejected", "gauge", "1 while the latest edit of routes.yaml was rejected and the previous routes still run.")
                .sample("r7_routes_config_rejected", rejected ? 1 : 0);

        out.metric("r7_unrouted_requests_total", "counter", "Requests no route matched.")
                .sample("r7_unrouted_requests_total", (long) json.get("unrouted_requests"));

        final ConnectorStatisticsDto connector = (ConnectorStatisticsDto) json.get("connector_statistics");
        if (connector != null)
        {
            out.metric("r7_connections_active", "gauge", "Open client connections to the gateway port.")
                    .sample("r7_connections_active", connector.activeConnections());
        }

        final Map<String, Object> journaling = (Map<String, Object>) json.get("journaling");
        out.metric("r7_journal_available_bytes", "gauge", "Free space for journals in the work directory.")
                .sample("r7_journal_available_bytes", ((Number) journaling.get("available_space")).longValue());

        routeMetrics(out);
        components(out, (List<RouteConfigDto>) json.get("route_configs"));

        final MemoryDto memory = (MemoryDto) ((Map<String, Object>) json.get("system")).get("memory");
        out.metric("r7_jvm_heap_used_bytes", "gauge", "Heap in use.").sample("r7_jvm_heap_used_bytes", memory.heapUsed());
        out.metric("r7_jvm_heap_max_bytes", "gauge", "The heap's limit.").sample("r7_jvm_heap_max_bytes", memory.heapMax());
        out.metric("r7_jvm_direct_used_bytes", "gauge", "Direct buffer memory in use.").sample("r7_jvm_direct_used_bytes", memory.directUsed());
        out.metric("r7_jvm_gc_seconds_total", "counter", "Time spent in garbage collection.")
                .sample("r7_jvm_gc_seconds_total", BigDecimal.valueOf(memory.gcTimeMs(), 3));
        out.metric("r7_process_open_fds", "gauge", "Open file descriptors.").sample("r7_process_open_fds", memory.openFds());
        out.metric("r7_process_max_fds", "gauge", "The file descriptor limit.").sample("r7_process_max_fds", memory.maxFds());
        return out.toBytes();
    }

    private void routeMetrics(final PrometheusText out)
    {
        final Map<String, RouteMetricsBucket> buckets = new TreeMap<>();
        this.metricsRegistry.forEachBucket(buckets::put);

        out.metric("r7_route_requests_total", "counter", "Responses sent to clients, by route and status code.");
        buckets.forEach((route, bucket) -> bucket.getClientResponseStatuses()
                .forEach((code, count) -> out.sample("r7_route_requests_total", count, "route", route, "code", Integer.toString(code))));

        out.metric("r7_route_upstream_responses_total", "counter", "Responses received from upstreams, by route and status code.");
        buckets.forEach((route, bucket) -> bucket.getUpstreamResponseStatuses()
                .forEach((code, count) -> out.sample("r7_route_upstream_responses_total", count, "route", route, "code", Integer.toString(code))));

        out.metric("r7_route_active_requests", "gauge", "Requests in progress, by route.");
        buckets.forEach((route, bucket) -> out.sample("r7_route_active_requests", bucket.getActiveRequests(), "route", route));

        out.metric("r7_route_active_websockets", "gauge", "Open WebSocket tunnels, by route.");
        buckets.forEach((route, bucket) -> out.sample("r7_route_active_websockets", bucket.getActiveWsRequests(), "route", route));

        out.metric("r7_route_journal_bytes_total", "counter", "Bytes written to the journal, by route.");
        buckets.forEach((route, bucket) -> out.sample("r7_route_journal_bytes_total", bucket.getTotalJournalBytes(), "route", route));

        out.metric("r7_route_request_duration_seconds", "histogram", "Time from request to response, by route, since the gateway started.");
        buckets.forEach((route, bucket) -> latencyHistogram(out, route, bucket.latencyWindow()));
    }

    static void latencyHistogram(final PrometheusText out, final String route, final LatencyWindow window)
    {
        final long[] counts = window.cumulativeCounts();
        final int overflow = counts.length - 1;
        long total = 0;
        for (final long count : counts)
        {
            total += count;
        }
        int bucket = 0;
        long below = 0;
        for (final long bound : LATENCY_BOUNDS_MICROS)
        {
            // The overflow bucket has no upper bound, so it only ever counts towards +Inf
            while (bucket < overflow && LatencyHistogram.upperBoundMicros(bucket) <= bound)
            {
                below += counts[bucket++];
            }
            out.sample("r7_route_request_duration_seconds_bucket", below, "route", route, "le", BigDecimal.valueOf(bound, 6).toPlainString());
        }
        out.sample("r7_route_request_duration_seconds_bucket", total, "route", route, "le", "+Inf");
        out.sample("r7_route_request_duration_seconds_sum", BigDecimal.valueOf(window.cumulativeNanos(), 9), "route", route);
        out.sample("r7_route_request_duration_seconds_count", total, "route", route);
    }

    /**
     * Each route's components that report a status: its filters, numbered by their place in the
     * route's pipeline (global filters first), and its upstream as position 0.
     */
    private static void components(final PrometheusText out, final List<RouteConfigDto> routes)
    {
        record Reported(String route, String component, String position, ComponentStatus status)
        {
        }
        final List<Reported> reported = new ArrayList<>();
        for (final RouteConfigDto route : routes)
        {
            int position = 1;
            for (FilterNode node = route.filterNodes(); node != null; node = node.child())
            {
                final boolean upstream = node.child() == null;
                if (node.status() != null)
                {
                    reported.add(new Reported(route.id(), upstream ? "upstream" : node.name(), upstream ? "0" : Integer.toString(position), node.status()));
                }
                position++;
            }
        }

        out.metric("r7_component_health", "gauge", "What a route's filter or upstream reports: 0 OK, 1 WARN, 2 ERROR.");
        for (final Reported r : reported)
        {
            out.sample("r7_component_health", healthValue(r.status().health()), "route", r.route(), "component", r.component(), "position", r.position());
        }
        out.metric("r7_component_value", "gauge", "A number a route's filter or upstream reports, by name.");
        for (final Reported r : reported)
        {
            r.status().values().forEach((name, value) ->
                    out.sample("r7_component_value", value, "route", r.route(), "component", r.component(), "position", r.position(), "name", name));
        }
    }

    private static long healthValue(final ComponentStatus.Health health)
    {
        return switch (health)
        {
            case OK -> 0;
            case WARN -> 1;
            case ERROR -> 2;
        };
    }

    private static long[] latencyBounds()
    {
        final long[] bounds = new long[17];
        for (int i = 0; i < bounds.length; i++)
        {
            bounds[i] = 1L << (8 + i);
        }
        return bounds;
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
