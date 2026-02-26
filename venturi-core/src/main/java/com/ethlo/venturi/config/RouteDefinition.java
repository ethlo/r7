package com.ethlo.venturi.config;

import java.util.List;

import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;

public record RouteDefinition(CharSequence id, List<CharSequence> uri, ConditionDefinition match, AuditDefinition audit,
                              List<FilterDefinition> filters) implements ValidatableConfig
{
    @Override
    public void validate(final ValidationResult result)
    {
        // 1. Validate ID
        if (id == null || id.toString().isBlank())
        {
            result.addError("id", "Route ID must not be empty");
        }

        // 2. Validate URIs
        if (uri == null || uri.isEmpty())
        {
            result.addError("uri", "At least one target URI is required");
        }
        else
        {
            uri.forEach(u ->
            {
                if (u == null || !u.toString().contains("://"))
                {
                    result.addError("uri", "Invalid URI format: " + u);
                }
            });
        }

        if (match != null)
        {
            match.validate(result);
        }

        if (audit != null)
        {
            audit.validate(result);
        }


        // 4. Validate Filters
        if (filters != null)
        {
            filters.forEach(f -> f.validate(result));
        }
    }
}