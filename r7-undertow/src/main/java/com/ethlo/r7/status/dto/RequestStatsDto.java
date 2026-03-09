package com.ethlo.r7.status.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record RequestStatsDto(
        long total,
        long active,
        @JsonProperty("websocket_total") long websocketTotal,
        @JsonProperty("websocket_active") long websocketActive,
        @JsonProperty("last_active_ts") long lastActive,
        @JsonProperty("upstream_statuses") Map<Integer, Long> upstreamResponseStatuses,
        @JsonProperty("client_statuses") Map<Integer, Long> clientResponseStatuses,
        long upstream)
{
}