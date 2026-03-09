package com.ethlo.r7.config;

import java.util.List;

import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

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