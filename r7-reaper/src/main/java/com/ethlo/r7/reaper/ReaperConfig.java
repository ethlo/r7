package com.ethlo.r7.reaper;

import java.time.Duration;
import java.util.Optional;

import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

/**
 * {@code reaper.yaml} - same {@code r7-config} conventions (snake_case, {@code
 * ${VAR:default}} interpolation, human duration units) as every other component in this
 * repository.
 * <p>
 * This is deliberately the dumbest retention policy that can work: age is the only signal.
 * There is no coordination with any tailer's checkpoint - a segment is reaped once it has sat
 * on disk, sealed, for longer than {@code ttl}, whether or not every tailer sharing the
 * directory has actually read it. Set {@code ttl} well beyond the slowest tailer's realistic
 * lag (restart time included) or a slow/down tailer will lose data it never got a chance to
 * read. A future, checkpoint-aware reaper can replace this one without changing how any
 * tailer is deployed - see {@code docs/journaling.md}.
 */
public record ReaperConfig(
        String journalDir,
        Duration ttl,
        Duration pollInterval
) implements ValidatableConfig
{
    public static ReaperConfig standard()
    {
        return new ReaperConfig(null, null, null);
    }

    @Override
    public String journalDir()
    {
        return Optional.ofNullable(this.journalDir).orElse("/journals");
    }

    @Override
    public Duration ttl()
    {
        return Optional.ofNullable(this.ttl).orElse(Duration.ofDays(7));
    }

    @Override
    public Duration pollInterval()
    {
        return Optional.ofNullable(this.pollInterval).orElse(Duration.ofMinutes(1));
    }

    @Override
    public void validate(final ValidationResult result)
    {
        if (this.journalDir != null && this.journalDir.isBlank())
        {
            result.addError("journal_dir", "must not be blank");
        }
        if (!ttl().isPositive())
        {
            result.addError("ttl", "must be positive, was " + ttl());
        }
        if (!pollInterval().isPositive())
        {
            result.addError("poll_interval", "must be positive, was " + pollInterval());
        }
    }
}
