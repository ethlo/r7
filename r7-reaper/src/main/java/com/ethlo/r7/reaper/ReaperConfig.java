package com.ethlo.r7.reaper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

/**
 * {@code reaper.yaml} - same {@code r7-config} conventions (snake_case, {@code
 * ${VAR:default}} interpolation, human duration units) as every other component in this
 * repository.
 * <p>
 * A sealed segment is reaped once it is older than {@code ttl}, whether or not every tailer has
 * read it: that is the bound on disk use, and it holds even for a tailer that is down. With
 * {@code tailers} listed it is reaped earlier, as soon as it is {@code min_age} old and every
 * listed tailer's checkpoint file says it is done with it (see {@code TailerProgress}). A
 * listed tailer whose file is missing or unreadable proves nothing, so only {@code ttl} applies
 * until it is back.
 */
public record ReaperConfig(
        String journalDir,
        Duration ttl,
        List<String> tailers,
        Duration minAge,
        Duration pollInterval
) implements ValidatableConfig
{
    public static ReaperConfig standard()
    {
        return new ReaperConfig(null, null, null, null, null);
    }

    /**
     * The checkpoint directories of every tailer that must be done with a segment before it is
     * reaped early. Empty: age is the only signal.
     */
    @Override
    public List<String> tailers()
    {
        return Optional.ofNullable(this.tailers).orElse(List.of());
    }

    /**
     * How old a segment must be before it is reaped early. A tailer records a segment as done
     * once its output reached the OS, not the disk; this keeps the journal's copy until the
     * tailers' output files have been sealed and fsync'd (their {@code max_file_age}, 15 minutes
     * by default), so a power loss cannot take both.
     */
    @Override
    public Duration minAge()
    {
        return Optional.ofNullable(this.minAge).orElse(Duration.ofHours(1));
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
        if (minAge().isNegative())
        {
            result.addError("min_age", "must not be negative, was " + minAge());
        }
        for (final String tailer : tailers())
        {
            if (tailer == null || tailer.isBlank())
            {
                result.addError("tailers", "must not contain a blank entry");
            }
        }
        if (!pollInterval().isPositive())
        {
            result.addError("poll_interval", "must be positive, was " + pollInterval());
        }
    }
}
