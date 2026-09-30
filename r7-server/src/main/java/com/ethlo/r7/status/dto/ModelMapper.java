package com.ethlo.r7.status.dto;

import com.ethlo.r7.journal.HeaderNameSet;
import com.ethlo.r7.util.FilterRegistry;
import com.ethlo.r7.util.SensitiveConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.config.DefaultGatewayRoute;
import com.ethlo.r7.config.RouteDefinition;
import com.ethlo.r7.config.TimeoutConfig;
import com.ethlo.r7.config.UpstreamConfig;
import com.ethlo.r7.predicates.CompositePredicate;
import com.ethlo.r7.status.PipelineVisualizer;
import com.ethlo.r7.status.RouteMetricsBucket;

public class ModelMapper
{
    private static final FilterRegistry FILTER_REGISTRY = new FilterRegistry();

    public static RouteConfigDto mapRouteConfig(final DefaultGatewayRoute route, final int order, final HeaderNameSet safeRequestHeaders, final HeaderNameSet safeResponseHeaders)
    {
        final RouteDefinition def = route.routeDefinition();

        final JournalDto journal = new JournalDto(
                def.journal().request().level(),
                def.journal().request().statusOverrides(),
                def.journal().response().level(),
                def.journal().response().statusOverrides()
        );

        final List<FilterDto> filters = def.filters() != null ? def.filters().stream()
                .map(f -> new FilterDto(f.name(), maskedArgs(f.name(), f.args(), safeRequestHeaders, safeResponseHeaders)))
                .toList() : Collections.emptyList();

        return new RouteConfigDto(
                def.id(),
                order,
                toPredicateNode(route.predicate()),
                journal,
                def.upstream() != null ? SensitiveConfig.redactUrlCredentials(def.upstream().targets().toString()) : null,
                toUpstream(def.upstream()),
                filters,
                PipelineVisualizer.buildNestedVisualization(route.routeDefinition().upstream(), route.filters().toArray(new GatewayFilter[0]), route.globalFilterCount())
        );
    }

    private static UpstreamDto toUpstream(final UpstreamConfig upstream)
    {
        if (upstream == null)
        {
            return null;
        }
        final List<String> targets = upstream.targets() != null
                ? upstream.targets().stream().map(t -> SensitiveConfig.redactUrlCredentials(t.url())).toList()
                : List.of();
        final Duration readTimeout = Optional.ofNullable(upstream.timeouts()).orElse(new TimeoutConfig(null)).read();
        final String fallbackRouteId = upstream.fallback() != null ? upstream.fallback().routeId() : null;
        return new UpstreamDto(targets, readTimeout, upstream.healthCheck(), fallbackRouteId);
    }

    /**
     * Without this the management endpoint renders filter configuration verbatim, putting upstream
     * credentials (InjectBasicAuth), password hashes (BasicAuth) and injected tokens on any page
     * that can reach it. Header values are shown by the same whitelist the journal uses - the
     * effective one, including any override in server.yaml.
     */
    static Object maskedArgs(final String filterName, final Object args, final HeaderNameSet safeRequestHeaders, final HeaderNameSet safeResponseHeaders)
    {
        return SensitiveConfig.mask(FILTER_REGISTRY.get(filterName).configClass(), args, safeRequestHeaders, safeResponseHeaders);
    }

    private static MatchDto toPredicateNode(final GatewayPredicate predicate)
    {
        if (predicate == null)
        {
            return null;
        }

        final List<MatchDto> childNodes = new ArrayList<>();
        final List<GatewayPredicate> children = predicate instanceof CompositePredicate compositePredicate ? compositePredicate.children() : null;
        if (children != null)
        {
            for (final GatewayPredicate child : children)
            {
                childNodes.add(toPredicateNode(child));
            }
        }

        return new MatchDto(
                predicate.name(),
                predicate.summary(),
                childNodes
        );
    }

    public static RouteMetricsDto routeMetrics(final Map.Entry<String, RouteMetricsBucket> routeMetricsBucket)
    {
        final RouteMetricsBucket gf = routeMetricsBucket.getValue();
        final TrafficFlowDto traffic = mapTraffic(gf);
        final PerformanceTelemetryDto performance = new PerformanceTelemetryDto(Duration.ofNanos(gf.getAvgLatencyNanos()));
        final RequestStatsDto stats = new RequestStatsDto(gf.getTotalRequests(), gf.getActiveRequests(),
                gf.getTotalWsRequests(), gf.getActiveWsRequests(), gf.getLastActiveTime(),
                gf.getUpstreamResponseStatuses(), gf.getClientResponseStatuses(),
                gf.getUpstreamRequests()
        );

        return new RouteMetricsDto(routeMetricsBucket.getKey(), stats, traffic, performance, gf.getSparklineData(), gf.getLatency());
    }

    private static TrafficFlowDto mapTraffic(RouteMetricsBucket routeMetricsBucket)
    {
        final IngressDto ingress = new IngressDto(
                routeMetricsBucket.getTotalRequestHeaderBytes(),
                routeMetricsBucket.getTotalRequestBodyBytes(),
                routeMetricsBucket.getTotalRequestHeaderBytes() + routeMetricsBucket.getTotalRequestBodyBytes()
        );

        final EgressDto egress = new EgressDto(
                routeMetricsBucket.getTotalResponseHeaderBytes(),
                routeMetricsBucket.getTotalResponseBodyBytes(),
                routeMetricsBucket.getTotalResponseHeaderBytes() + routeMetricsBucket.getTotalResponseBodyBytes()
        );

        return new TrafficFlowDto(ingress, egress, routeMetricsBucket.getTotalJournalBytes());
    }
}
