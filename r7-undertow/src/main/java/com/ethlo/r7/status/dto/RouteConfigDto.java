package com.ethlo.r7.status.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @param order 1-based position in routes.yaml, which is the order routes are tried in: the first
 *              whose predicates match takes the request
 */
public record RouteConfigDto(
        String id,
        int order,
        MatchDto match,
        JournalDto journal,
        String destination,
        UpstreamDto upstream,
        List<FilterDto> filters,
        @JsonProperty("filter_nodes")
        FilterNode filterNodes
)
{
}
