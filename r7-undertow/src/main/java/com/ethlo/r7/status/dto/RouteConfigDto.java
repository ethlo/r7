package com.ethlo.r7.status.dto;

import java.util.List;

public record RouteConfigDto(
        String id,
        MatchDto match,
        JournalDto journal,
        String destination,
        List<FilterDto> filters
)
{
}

