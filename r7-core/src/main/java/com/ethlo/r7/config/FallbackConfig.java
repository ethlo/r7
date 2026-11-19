package com.ethlo.r7.config;

import com.ethlo.r7.doc.Description;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

@Description("Fallback routing behavior triggered when all upstream targets fail.")
public record FallbackConfig(
        @Description("The ID of another route to execute if this upstream becomes completely unavailable.")
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