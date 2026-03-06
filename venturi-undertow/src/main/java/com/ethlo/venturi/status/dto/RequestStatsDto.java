package com.ethlo.venturi.status.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RequestStatsDto(
        long total,
        long active,
        @JsonProperty("websocket_total") long websocketTotal,
        @JsonProperty("websocket_active") long websocketActive,
        @JsonProperty("last_active_ts") long lastActive,
        @JsonProperty("status_2xx") long status2xx,
        @JsonProperty("status_3xx") long status3xx,
        @JsonProperty("status_4xx") long status4xx,
        @JsonProperty("status_5xx") long status5xx,
        long upstream)
{
}