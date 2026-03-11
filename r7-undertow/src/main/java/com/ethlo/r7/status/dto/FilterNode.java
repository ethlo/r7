package com.ethlo.r7.status.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FilterNode(
        String id,
        @JsonProperty("on_client_request")
        boolean onClientRequest,
        @JsonProperty("on_upstream_request")
        boolean onUpstreamRequest,
        @JsonProperty("on_client_response")
        boolean onClientResponse,
        @JsonProperty("on_completed")
        boolean onCompleted,
        FilterNode child
)
{
}