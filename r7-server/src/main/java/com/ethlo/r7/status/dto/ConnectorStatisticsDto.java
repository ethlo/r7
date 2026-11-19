package com.ethlo.r7.status.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ConnectorStatisticsDto(
        @JsonProperty("request_count") long requestCount,
        @JsonProperty("bytes_sent") long bytesSent,
        @JsonProperty("bytes_received") long bytesReceived,
        @JsonProperty("error_count") long errorCount,
        @JsonProperty("processing_time_nanos") long processingTimeNanos,
        @JsonProperty("max_processing_time_nanos") long maxProcessingTimeNanos,
        @JsonProperty("active_connections") long activeConnections,
        @JsonProperty("max_active_connections") long maxActiveConnections,
        @JsonProperty("active_requests") long activeRequests,
        @JsonProperty("max_active_requests") long maxActiveRequests
)
{

}