package com.ethlo.r7.config;

import java.util.List;

import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public record UpstreamConfig(
        Strategy strategy,
        HealthCheckConfig healthCheck,
        TimeoutConfig timeouts,
        List<TargetConfig> targets,
        FallbackConfig fallback
) implements ValidatableConfig
{
    @Override
    public void validate(final ValidationResult result)
    {
        final ValidatorUtils validator = new ValidatorUtils(result);

        // Ensure the targets list itself is defined and contains at least one node
        if (this.targets == null || this.targets.isEmpty())
        {
            validator.invalid("targets", null, "Upstream configuration must contain at least one target target configuration");
        }
        else
        {
            // Cascade validation down through each target item in the collection
            for (int i = 0; i < this.targets.size(); i++)
            {
                final TargetConfig target = this.targets.get(i);
                target.validate(result.nested("targets[" + i + "]"));
            }
        }

        if (this.healthCheck != null)
        {
            this.healthCheck.validate(result.nested("health_check"));
        }

        if (this.timeouts != null)
        {
            this.timeouts.validate(result.nested("timeouts"));
        }

        if (this.fallback != null)
        {
            this.fallback.validate(result.nested("fallback"));
        }
    }
}