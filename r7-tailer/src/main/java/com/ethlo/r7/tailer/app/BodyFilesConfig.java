package com.ethlo.r7.tailer.app;

import java.time.Duration;
import java.util.Optional;

import com.ethlo.r7.config.model.DataSize;
import com.ethlo.r7.tailer.files.RollingFilesConfig;
import com.ethlo.r7.tailer.json.BodyFiles;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

/**
 * The {@code json.body_files} block of {@code tailer.yaml}: where {@code json.bodies: files}
 * stores bodies, one Zstandard frame per body in files rolled on size or age.
 */
public record BodyFilesConfig(
        String outputDir,
        String filePrefix,
        DataSize maxFileSize,
        Duration maxFileAge,
        Integer zstdLevel,
        Integer dedupCacheEntries
) implements RollingFilesConfig, ValidatableConfig
{
    public static BodyFilesConfig standard()
    {
        return new BodyFilesConfig(null, null, null, null, null, null);
    }

    @Override
    public String outputDir()
    {
        return Optional.ofNullable(this.outputDir).orElse("/bodies");
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

    /**
     * Level 3: for one body at a time, a higher level saves about 1% at three times the CPU.
     */
    public Integer zstdLevel()
    {
        return Optional.ofNullable(this.zstdLevel).orElse(3);
    }

    public Integer dedupCacheEntries()
    {
        return Optional.ofNullable(this.dedupCacheEntries).orElse(100_000);
    }

    @Override
    public void validate(final ValidationResult result)
    {
        final ValidatorUtils v = new ValidatorUtils(result);
        if (this.zstdLevel() < BodyFiles.MIN_ZSTD_LEVEL || this.zstdLevel() > BodyFiles.MAX_ZSTD_LEVEL)
        {
            result.addError("zstd_level", "must be between " + BodyFiles.MIN_ZSTD_LEVEL + " and " + BodyFiles.MAX_ZSTD_LEVEL
                    + ", but was " + this.zstdLevel());
        }
        v.requirePositive("dedup_cache_entries", this.dedupCacheEntries());
        this.validateRollover(result);
    }
}
