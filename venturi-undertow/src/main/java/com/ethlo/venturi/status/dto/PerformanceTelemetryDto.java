package com.ethlo.venturi.status.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PerformanceTelemetryDto(
        @JsonProperty("average_latency_nanoseconds") long averageLatencyNanoseconds
)
{
}
