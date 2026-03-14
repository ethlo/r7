package com.ethlo.r7.config;

import java.util.List;
import java.util.Optional;

import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public record RoutesConfig(
        String version,
        List<FilterDefinition> filters,
        List<RouteDefinition> routes
) implements ValidatableConfig, VersionedConfig
{
    @Override
    public void validate(final ValidationResult result)
    {
        for (final RouteDefinition route : routes)
        {
            route.validate(result);
        }
    }

    @Override
    public List<RouteDefinition> routes()
    {
        return Optional.ofNullable(routes).orElse(List.of());
    }

    @Override
    public List<FilterDefinition> filters()
    {
        return Optional.ofNullable(filters).orElse(List.of());
    }
}