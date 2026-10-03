package com.ethlo.r7.tailer.app;

import java.time.Duration;
import java.util.Optional;

import com.ethlo.r7.tailer.TailerConfig;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

/**
 * {@code tailer.yaml}: the reader settings at the top level, and one block per output. Same
 * {@code r7-config} conventions (snake_case, {@code ${VAR:default}} interpolation, human
 * duration and size units) as the gateway's {@code routes.yaml}/{@code server.yaml}.
 * <p>
 * There is deliberately no {@code ttl}/{@code grace_period} here: the tailer never deletes a
 * segment. Retention is the reaper's job; mount {@code journal_dir} read-only here to make that
 * enforced rather than merely intended.
 */
public record TailerAppConfig(
        String journalDir,
        String checkpointDir,
        Duration pollInterval,
        WarcOutputConfig warc,
        JsonOutputConfig json
) implements TailerConfig, ValidatableConfig
{
    public static TailerAppConfig standard()
    {
        return new TailerAppConfig(null, null, null, null, null);
    }

    @Override
    public String journalDir()
    {
        return Optional.ofNullable(this.journalDir).orElse(DEFAULT_JOURNAL_DIR);
    }

    @Override
    public String checkpointDir()
    {
        return Optional.ofNullable(this.checkpointDir).orElse(DEFAULT_CHECKPOINT_DIR);
    }

    @Override
    public Duration pollInterval()
    {
        return Optional.ofNullable(this.pollInterval).orElse(DEFAULT_POLL_INTERVAL);
    }

    /**
     * Off unless enabled: it needs a volume to write to.
     */
    public WarcOutputConfig warc()
    {
        return Optional.ofNullable(this.warc).orElseGet(WarcOutputConfig::standard);
    }

    /**
     * On, to standard output, unless configured otherwise.
     */
    public JsonOutputConfig json()
    {
        return Optional.ofNullable(this.json).orElseGet(JsonOutputConfig::standard);
    }

    @Override
    public void validate(final ValidationResult result)
    {
        if (this.pollInterval().isNegative() || this.pollInterval().isZero())
        {
            result.addError("poll_interval", "must be positive, but was " + this.pollInterval());
        }
        this.warc().validate(result.nested("warc"));
        this.json().validate(result.nested("json"));
        if (!this.warc().enabled() && !this.json().enabled())
        {
            result.addError("json.enabled", "at least one of warc and json must be enabled, or the tailer writes nothing");
        }
    }
}
