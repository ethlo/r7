package com.ethlo.r7.tailer.warc;

import java.time.Duration;
import java.util.Optional;

import com.ethlo.r7.config.model.DataSize;
import com.ethlo.r7.tailer.TailerConfig;
import com.ethlo.r7.tailer.files.RollingFilesConfig;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

/**
 * {@code warc-tailer.yaml} - same {@code r7-config} conventions (snake_case, {@code
 * ${VAR:default}} interpolation, human duration/size units) as the gateway's {@code
 * routes.yaml}/{@code server.yaml}, so an operator moving between the two never has to learn a
 * second config language. Env-var overrides for a containerized deployment still work exactly
 * as they do for the gateway - interpolate them into the YAML file, e.g. {@code
 * journal_dir: ${JOURNAL_DIR:/journals}} - rather than reading them directly in code.
 * <p>
 * There is deliberately no {@code ttl}/{@code grace_period} here: this tailer never deletes a
 * segment (see {@code WarcTailerMain}, which constructs {@code R7Tailer} with no retention
 * parameters at all - the class has none). Retention is a dedicated reaper's job, applied
 * uniformly to every format-specific tailer sharing the journal directory; mount {@code
 * journal_dir} read-only here to make that enforced rather than merely intended.
 */
public record WarcTailerConfig(
        String journalDir,
        String outputDir,
        String checkpointDir,
        String filePrefix,
        DataSize maxFileSize,
        Duration maxFileAge,
        Integer zstdLevel,
        Integer dedupCacheEntries,
        Duration pollInterval,
        Boolean cdxjIndex
) implements TailerConfig, RollingFilesConfig, ValidatableConfig
{
    public static WarcTailerConfig standard()
    {
        return new WarcTailerConfig(null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public String journalDir()
    {
        return Optional.ofNullable(this.journalDir).orElse(DEFAULT_JOURNAL_DIR);
    }

    @Override
    public String outputDir()
    {
        return Optional.ofNullable(this.outputDir).orElse("/warc");
    }

    @Override
    public String checkpointDir()
    {
        return Optional.ofNullable(this.checkpointDir).orElse(DEFAULT_CHECKPOINT_DIR);
    }

    @Override
    public String filePrefix()
    {
        return Optional.ofNullable(this.filePrefix).orElse(DEFAULT_FILE_PREFIX);
    }

    @Override
    public DataSize maxFileSize()
    {
        return Optional.ofNullable(this.maxFileSize).orElse(DataSize.ofGigabytes(1));
    }

    @Override
    public Duration maxFileAge()
    {
        return Optional.ofNullable(this.maxFileAge).orElse(DEFAULT_MAX_FILE_AGE);
    }

    @Override
    public Integer zstdLevel()
    {
        return Optional.ofNullable(this.zstdLevel).orElse(9);
    }

    @Override
    public Integer dedupCacheEntries()
    {
        return Optional.ofNullable(this.dedupCacheEntries).orElse(100_000);
    }

    @Override
    public Duration pollInterval()
    {
        return Optional.ofNullable(this.pollInterval).orElse(DEFAULT_POLL_INTERVAL);
    }

    @Override
    public Boolean cdxjIndex()
    {
        return Optional.ofNullable(this.cdxjIndex).orElse(false);
    }

    @Override
    public void validate(final ValidationResult result)
    {
        final ValidatorUtils v = new ValidatorUtils(result);
        v.requirePositive("zstd_level", this.zstdLevel());
        v.requirePositive("dedup_cache_entries", this.dedupCacheEntries());

        this.validateRollover(result);
    }
}
