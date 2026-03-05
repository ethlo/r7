package com.ethlo.venturi.status.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EgressDto(
        @JsonProperty("header_bytes") long headerBytes,
        @JsonProperty("body_bytes") long bodyBytes,
        @JsonProperty("total_bytes") long totalBytes
)
{
}
