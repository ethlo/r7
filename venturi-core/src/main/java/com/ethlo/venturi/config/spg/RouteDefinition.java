package com.ethlo.venturi.config.spg;

import java.util.List;

public record RouteDefinition(
        String id,
        String uri,
        List<PredicateDefinition> predicates,
        List<FilterDefinition> filters
)
{
}

