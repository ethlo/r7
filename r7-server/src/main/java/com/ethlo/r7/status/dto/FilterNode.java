package com.ethlo.r7.status.dto;

import com.ethlo.r7.api.ComponentStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @param level  where the filter was declared: {@code root} (one instance for the gateway),
 *               {@code group} (one for the routes of its group) or {@code route}; {@code route}
 *               for the upstream
 * @param status what the filter, or the upstream, reports about itself; null when it reports nothing
 */
public record FilterNode(
        String name,
        String summary,
        String level,
        @JsonProperty("on_client_request")
        boolean onClientRequest,
        @JsonProperty("on_upstream_request")
        boolean onUpstreamRequest,
        @JsonProperty("on_client_response")
        boolean onClientResponse,
        @JsonProperty("on_completed")
        boolean onCompleted,
        ComponentStatus status,
        FilterNode child
)
{
}
