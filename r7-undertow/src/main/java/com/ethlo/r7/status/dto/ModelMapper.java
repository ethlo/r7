package com.ethlo.r7.status.dto;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.config.DefaultGatewayRoute;
import com.ethlo.r7.config.RouteDefinition;
import com.ethlo.r7.predicates.CompositePredicate;
import com.ethlo.r7.status.PipelineVisualizer;
import com.ethlo.r7.status.SimpleMetricsFactory;
import io.undertow.server.ConnectorStatistics;

public class ModelMapper
{
    public static RouteConfigDto mapRouteConfig(final DefaultGatewayRoute route)
    {
        final RouteDefinition def = route.routeDefinition();

        final JournalDto journal = new JournalDto(
                def.journal().request().level(),
                def.journal().request().statusOverrides(),
                def.journal().response().level(),
                def.journal().response().statusOverrides()
        );

        final List<FilterDto> filters = def.filters() != null ? def.filters().stream()
                .map(f -> new FilterDto(f.name(), f.args()))
                .toList() : Collections.emptyList();

        return new RouteConfigDto(
                def.id().toString(),
                toPredicateNode(route.predicate()),
                journal,
                def.upstream().targets().toString(),
                filters,
                PipelineVisualizer.buildNestedVisualization(route.routeDefinition().upstream(), route.filters().toArray(new GatewayFilter[0]))
        );
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

    public static RouteMetricsDto routeMetrics(final Map.Entry<String, SimpleMetricsFactory.GF> gfEntry)
    {
        final SimpleMetricsFactory.GF gf = gfEntry.getValue();
        final TrafficFlowDto traffic = mapTraffic(gf);
        final PerformanceTelemetryDto performance = new PerformanceTelemetryDto(Duration.ofNanos(gf.getAvgLatencyNanos()));
        final RequestStatsDto stats = new RequestStatsDto(gf.getTotalRequests(), gf.getActiveRequests(),
                gf.getTotalWsRequests(), gf.getActiveWsRequests(), gf.getLastActiveTime(),
                gf.getUpstreamResponseStatuses(), gf.getClientResponseStatuses(),
                gf.getUpstreamRequests()
        );

        return new RouteMetricsDto(gfEntry.getKey(), stats, traffic, performance, gf.getSparklineData());
    }

    private static TrafficFlowDto mapTraffic(SimpleMetricsFactory.GF gf)
    {
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

        return new TrafficFlowDto(ingress, egress, gf.getTotalJournalBytes());
    }

    public static ConnectorStatisticsDto from(final ConnectorStatistics stats)
    {
        if (stats == null)
        {
            return null;
        }

        return new ConnectorStatisticsDto(
                stats.getRequestCount(),
                stats.getBytesSent(),
                stats.getBytesReceived(),
                stats.getErrorCount(),
                stats.getProcessingTime(),
                stats.getMaxProcessingTime(),
                stats.getActiveConnections(),
                stats.getMaxActiveConnections(),
                stats.getActiveRequests(),
                stats.getMaxActiveRequests()
        );
    }
}
