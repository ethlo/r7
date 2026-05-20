package com.ethlo.r7.config;

import java.util.List;
import java.util.Optional;

import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public record RoutesDefinition(
        String version,
        List<FilterDefinition> globalFilters,
        List<RouteDefinition> routes
) implements ValidatableConfig, VersionedConfig
{
    @Override
    public void validate(final ValidationResult result)
    {
        for (final RouteDefinition route : routes)
        {
            route.validate(result.nested(route.id().toString()));
        }
    }

    @Override
    public List<RouteDefinition> routes()
    {
        return Optional.ofNullable(routes).orElse(List.of());
    }

    @Override
    public List<FilterDefinition> globalFilters()
    {
        return Optional.ofNullable(globalFilters).orElse(List.of());
    }
}