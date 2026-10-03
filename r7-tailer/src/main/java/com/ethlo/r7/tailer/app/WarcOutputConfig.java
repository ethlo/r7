package com.ethlo.r7.tailer.app;

import java.time.Duration;
import java.util.Optional;

import com.ethlo.r7.config.model.DataSize;
import com.ethlo.r7.tailer.files.RollingFilesConfig;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.ethlo.r7.warc.WarcFileWriter;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The {@code warc} block of {@code tailer.yaml}: WARC files, rolled on size or age.
 */
public record WarcOutputConfig(
        Boolean enabled,
        Exchanges exchanges,
        String outputDir,
        String filePrefix,
        DataSize maxFileSize,
        Duration maxFileAge,
        Integer zstdLevel,
        Integer dedupCacheEntries,
        Boolean cdxjIndex,
        Boolean bodies
) implements RollingFilesConfig, ValidatableConfig
{
    /**
     * Which exchanges get WARC records.
     */
    public enum Exchanges
    {
        /**
         * Every exchange: the WARC files are the complete archive, and every JSON line points
         * into them.
         */
        @JsonProperty("all")
        ALL,

        /**
         * Only exchanges with a captured request or response body: the WARC files store bodies,
         * and the JSON lines are the full log.
         */
        @JsonProperty("with_body")
        WITH_BODY
    }

    public static WarcOutputConfig standard()
    {
        return new WarcOutputConfig(null, null, null, null, null, null, null, null, null, null);
    }

    public Boolean enabled()
    {
        return Optional.ofNullable(this.enabled).orElse(false);
    }

    public Exchanges exchanges()
    {
        return Optional.ofNullable(this.exchanges).orElse(Exchanges.ALL);
    }

    @Override
    public String outputDir()
    {
        return Optional.ofNullable(this.outputDir).orElse("/warc");
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

    public Integer zstdLevel()
    {
        return Optional.ofNullable(this.zstdLevel).orElse(9);
    }

    public Integer dedupCacheEntries()
    {
        return Optional.ofNullable(this.dedupCacheEntries).orElse(100_000);
    }

    public Boolean cdxjIndex()
    {
        return Optional.ofNullable(this.cdxjIndex).orElse(false);
    }

    public Boolean bodies()
    {
        return Optional.ofNullable(this.bodies).orElse(true);
    }

    @Override
    public void validate(final ValidationResult result)
    {
        final ValidatorUtils v = new ValidatorUtils(result);
        if (this.zstdLevel() < WarcFileWriter.MIN_ZSTD_LEVEL || this.zstdLevel() > WarcFileWriter.MAX_ZSTD_LEVEL)
        {
            result.addError("zstd_level", "must be between " + WarcFileWriter.MIN_ZSTD_LEVEL + " and " + WarcFileWriter.MAX_ZSTD_LEVEL
                    + ", but was " + this.zstdLevel());
        }
        v.requirePositive("dedup_cache_entries", this.dedupCacheEntries());
        this.validateRollover(result);
    }
}
