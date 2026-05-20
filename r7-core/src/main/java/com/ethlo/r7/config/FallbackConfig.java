package com.ethlo.r7.config;

import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public record FallbackConfig(
        String routeId
) implements ValidatableConfig
{
    @Override
    public void validate(final ValidationResult result)
    {
        final ValidatorUtils validator = new ValidatorUtils(result);

        // If a fallback object is declared, it currently MUST have a routeId
        validator.required("route_id", this.routeId);
    }
}