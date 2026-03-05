package com.ethlo.venturi.status.dto;

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

