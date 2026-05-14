package com.ethlo.r7.config;

import java.util.List;

import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public record UpstreamConfig(
        Strategy strategy,
        HealthCheckConfig healthCheck,
        TimeoutConfig timeouts,
        List<TargetConfig> targets
) implements ValidatableConfig
{
    @Override
    public void validate(final ValidationResult result)
    {
        // TODO: Implement me
    }
}
