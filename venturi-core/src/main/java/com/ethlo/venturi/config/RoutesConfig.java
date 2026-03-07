package com.ethlo.venturi.config;

import java.util.List;

import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;

public record RoutesConfig(String version, List<RouteDefinition> routes) implements ValidatableConfig, VersionedConfig
{
    @Override
    public void validate(final ValidationResult result)
    {
        for (final RouteDefinition route : routes)
        {
            route.validate(result);
        }
    }
}