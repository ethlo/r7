package com.ethlo.r7.status.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TrafficFlowDto(
        IngressDto ingress,
        EgressDto egress,
        @JsonProperty("journal_storage_bytes") long journalStorageBytes
)
{
}
