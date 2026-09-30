package com.ethlo.r7.status.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;

public record PerformanceTelemetryDto(@JsonProperty("average_latency") Duration averageLatency)
{
}
