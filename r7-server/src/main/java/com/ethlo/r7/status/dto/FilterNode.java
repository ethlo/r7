package com.ethlo.r7.status.dto;

import com.ethlo.r7.api.ComponentStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @param global true for an instance of one of routes.yaml's {@code global_filters}
 * @param status what the filter, or the upstream, reports about itself; null when it reports nothing
 */
public record FilterNode(
        String name,
        String summary,
        boolean global,
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
