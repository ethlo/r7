package com.ethlo.venturi.config;

import java.util.List;

import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;

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
