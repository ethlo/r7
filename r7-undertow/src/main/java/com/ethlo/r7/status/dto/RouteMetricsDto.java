package com.ethlo.r7.status.dto;

import com.ethlo.r7.status.SparklineRingBuffer;
import com.fasterxml.jackson.annotation.JsonProperty;

public record RouteMetricsDto(
        String id,

        @JsonProperty("request_statistics")
        RequestStatsDto requestStatistics,

        @JsonProperty("traffic_flow")
        TrafficFlowDto trafficFlow,

        @JsonProperty("performance_telemetry")
        PerformanceTelemetryDto performanceTelemetry,

        @JsonProperty("sparkline_data")
        SparklineRingBuffer.SparklineSnapshot sparklineData)
{
}

