package com.ethlo.r7.status.dto;

import java.util.List;

public record MatchDto(String name,
                       String summary,
                       List<MatchDto> children)
{
}
