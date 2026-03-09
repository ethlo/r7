package com.ethlo.r7.status.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PerformanceTelemetryDto(
        @JsonProperty("average_latency_nanoseconds") long averageLatencyNanoseconds
)
{
}
