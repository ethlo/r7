package com.ethlo.r7.config;

import java.time.Duration;
import java.util.Optional;

import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

/**
 * Configuration for background health probes
 */
public record HealthCheckConfig(
        String path,
        Duration interval,
        Integer fall,
        Integer rise,
        TargetStateOverride override
) implements ValidatableConfig
{
    private static final String CONFIG_NAME = "health_check";

    @Override
    public void validate(final ValidationResult result)
    {
        final ValidatorUtils validator = new ValidatorUtils(result);

        if (this.path != null && !this.path.startsWith("/"))
        {
            validator.invalid("path", this.path, "Health check path must begin with a forward slash '/'");
        }

        if (this.interval != null && (this.interval.isNegative() || this.interval.isZero()))
        {
            validator.invalid("interval", this.interval, "Interval duration must be positive");
        }

        if (this.rise != null && this.rise < 1)
        {
            validator.invalid("rise", this.rise, "Rise threshold must be greater than or equal to 1");
        }

        if (this.fall != null && this.fall < 1)
        {
            validator.invalid("fall", this.fall, "Fall threshold must be greater than or equal to 1");
        }
    }

    @Override
    public String path()
    {
        return Optional.ofNullable(this.path).orElse("/health");
    }

    @Override
    public Integer rise()
    {
        return Optional.ofNullable(this.rise).orElse(2);
    }

    @Override
    public Integer fall()
    {
        return Optional.ofNullable(this.fall).orElse(2);
    }

    @Override
    public Duration interval()
    {
        return Optional.ofNullable(this.interval).orElse(Duration.ofSeconds(10));
    }

    @Override
    public TargetStateOverride override()
    {
        return Optional.ofNullable(this.override).orElse(TargetStateOverride.NONE);
    }

    public enum TargetStateOverride
    {
        NONE,
        FORCE_UP,
        FORCE_DOWN
    }
}