package com.ethlo.r7.config;

import java.time.Duration;
import java.util.Optional;

/**
 * Configuration for background health probes
 *
 * @param path     The endpoint to check (e.g., /health)
 * @param interval The duration between checks (e.g., 5s)
 */
public record HealthCheckConfig(
        String path,
        Duration interval,
        Integer fall,
        Integer rise,
        TargetStateOverride override
)
{
    @Override
    public String path()
    {
        return Optional.ofNullable(path).orElse("/health");
    }

    @Override
    public Integer rise()
    {
        return Optional.ofNullable(rise).orElse(2);
    }

    @Override
    public Integer fall()
    {
        return Optional.ofNullable(fall).orElse(2);
    }

    @Override
    public Duration interval()
    {
        return Optional.ofNullable(interval).orElse(Duration.ofSeconds(10));
    }

    @Override
    public TargetStateOverride override()
    {
        return Optional.ofNullable(override).orElse(TargetStateOverride.NONE);
    }

    public enum TargetStateOverride
    {
        NONE,
        FORCE_UP,
        FORCE_DOWN
    }
}