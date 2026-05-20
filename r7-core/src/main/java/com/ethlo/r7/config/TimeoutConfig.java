package com.ethlo.r7.config;

import java.time.Duration;
import java.util.Optional;

import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

/**
 * Networking limits for the upstream proxy connection
 *
 * @param read Maximum time to wait for a response from the target
 */
public record TimeoutConfig(
        Duration read
) implements ValidatableConfig
{
    @Override
    public Duration read()
    {
        return Optional.ofNullable(this.read).orElse(Duration.ofSeconds(30));
    }

    @Override
    public void validate(final ValidationResult result)
    {
        if (this.read != null && (this.read.isNegative() || this.read.isZero()))
        {
            result.addError("read", "Read timeout must be greater than 0");
        }
    }
}