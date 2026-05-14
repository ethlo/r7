package com.ethlo.r7.status.dto;

import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PerformanceTelemetryDto(@JsonProperty("average_latency") Duration averageLatency)
{
}
