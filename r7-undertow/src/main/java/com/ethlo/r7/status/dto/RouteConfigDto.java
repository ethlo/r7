package com.ethlo.r7.status.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RouteConfigDto(
        String id,
        MatchDto match,
        JournalDto journal,
        String destination,
        List<FilterDto> filters,
        @JsonProperty("filter_nodes")
        FilterNode filterNodes
)
{
}

