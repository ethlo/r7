package com.ethlo.venturi.status.dto;

import com.ethlo.venturi.config.RouteDefinition;
import com.ethlo.venturi.status.SimpleMetricsFactory;
import io.undertow.server.ConnectorStatistics;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ModelMapper
{
    public static  RouteConfigDto mapToDto(final RouteDefinition def)
    {
        final MatchDto match = new MatchDto(
                def.match().getClass().getSimpleName(),
                def.match().toString()
        );

        final JournalDto journal = new JournalDto(
                def.journal().request().level().name(),
                def.journal().response().level().name()
        );

        final List<FilterDto> filters = def.filters() != null ? def.filters().stream()
                .map(f -> new FilterDto(f.name(), f.args()))
                .toList() : Collections.emptyList();

        return new RouteConfigDto(
                def.id().toString(),
                match,
                journal,
                def.upstream().targets().toString(),
                filters
        );
    }

    public static RouteMetricsDto mapToDto(final Map.Entry<String, SimpleMetricsFactory.GF> gfEntry)
    {
        final SimpleMetricsFactory.GF gf = gfEntry.getValue();
        final TrafficFlowDto traffic = mapTraffic(gf);
        final PerformanceTelemetryDto performance = new PerformanceTelemetryDto(gf.getAvgLatencyNanos());
        final RequestStatsDto stats = new RequestStatsDto(gf.getTotalRequests(), gf.getActiveRequests(),
                gf.getTotalWsRequests(), gf.getActiveWsRequests(), gf.getLastActiveTime(),
                gf.getUpstreamResponseStatuses(), gf.getClientResponseStatuses(),
                gf.getUpstreamRequests()
        );

        return new RouteMetricsDto(gfEntry.getKey(), stats, traffic, performance);
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
