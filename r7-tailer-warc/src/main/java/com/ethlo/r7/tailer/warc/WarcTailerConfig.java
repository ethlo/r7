package com.ethlo.r7.tailer.warc;

import java.time.Duration;
import java.util.Optional;

import com.ethlo.r7.config.model.DataSize;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.ethlo.r7.warc.WarcFileWriter;

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
) implements ValidatableConfig
{
    public static WarcTailerConfig standard()
    {
        return new WarcTailerConfig(null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public String journalDir()
    {
        return Optional.ofNullable(this.journalDir).orElse("/journals");
    }

    @Override
    public String outputDir()
    {
        return Optional.ofNullable(this.outputDir).orElse("/warc");
    }

    @Override
    public String checkpointDir()
    {
        // Its own volume, as for the JSON tailer: not under journalDir(), which a tailer mounts
        // read-only, and not under outputDir(), because a reaper reads this directory to learn
        // which segments the tailer is done with, and must not need access to the archive to
        // do it.
        return Optional.ofNullable(this.checkpointDir).orElse("/checkpoints");
    }

    @Override
    public String filePrefix()
    {
        return Optional.ofNullable(this.filePrefix).orElse("r7");
    }

    @Override
    public DataSize maxFileSize()
    {
        return Optional.ofNullable(this.maxFileSize).orElse(DataSize.ofGigabytes(1));
    }

    @Override
    public Duration maxFileAge()
    {
        return Optional.ofNullable(this.maxFileAge).orElse(Duration.ofMinutes(15));
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
        return Optional.ofNullable(this.pollInterval).orElse(Duration.ofSeconds(1));
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

        final long maxFileSizeBytes = this.maxFileSize().bytes();
        if (maxFileSizeBytes < WarcFileWriter.MIN_ROLLOVER_SIZE)
        {
            result.addError("max_file_size", "must be at least " + WarcFileWriter.MIN_ROLLOVER_SIZE
                    + " bytes, but was " + maxFileSizeBytes);
        }
    }
}
