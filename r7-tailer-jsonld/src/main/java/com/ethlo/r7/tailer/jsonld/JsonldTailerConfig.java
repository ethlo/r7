package com.ethlo.r7.tailer.jsonld;

import java.time.Duration;
import java.util.Optional;

import com.ethlo.r7.config.model.DataSize;

import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

/**
 * {@code jsonld-tailer.yaml} - same {@code r7-config} conventions (snake_case, {@code
 * ${VAR:default}} interpolation, human duration units) as the gateway's {@code
 * routes.yaml}/{@code server.yaml}, so an operator moving between the two never has to learn a
 * second config language. Env-var overrides for a containerized deployment still work exactly
 * as they do for the gateway - interpolate them into the YAML file, e.g. {@code
 * journal_dir: ${JOURNAL_DIR:/journals}} - rather than reading them directly in code.
 * <p>
 * There is deliberately no {@code ttl}/{@code grace_period} here: this tailer never deletes a
 * segment (see {@code TailerMain}, which constructs {@code R7Tailer} with no retention
 * parameters at all - the class has none). Retention is a dedicated reaper's job, applied
 * uniformly to every format-specific tailer sharing the journal directory; mount {@code
 * journal_dir} read-only here to make that enforced rather than merely intended.
 */
public record JsonldTailerConfig(
        String journalDir,
        String checkpointDir,
        String outputPath,
        String outputDir,
        String filePrefix,
        DataSize maxFileSize,
        Duration maxFileAge,
        Boolean prettyPrint,
        Boolean hideEmptyFields,
        Duration pollInterval
) implements ValidatableConfig
{
    public static JsonldTailerConfig standard()
    {
        return new JsonldTailerConfig(null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public String journalDir()
    {
        return Optional.ofNullable(this.journalDir).orElse("/journals");
    }

    @Override
    public String checkpointDir()
    {
        // Not under journalDir(): a secondary tailer (one not responsible for retention -
        // R7Tailer itself never deletes anything, see the class javadoc) must be free to
        // mount journalDir read-only, which a checkpoint file living inside it would rule
        // out. outputPath() is not a reliable fallback directory either - it defaults to
        // stdout - so this gets its own dedicated, always-writable location instead.
        return Optional.ofNullable(this.checkpointDir).orElse("/checkpoints");
    }

    /**
     * {@code -} or {@code stdout} (the default) means standard output, so it can be picked up
     * by whatever log forwarder the deployment already runs.
     */
    @Override
    public String outputPath()
    {
        return Optional.ofNullable(this.outputPath).orElse("-");
    }

    /**
     * When set, records go to rotating files in this directory instead of {@link #outputPath()}:
     * rolled on {@link #maxFileSize()} or {@link #maxFileAge()}, whichever comes first, and
     * renamed from {@code .jsonl.open} to {@code .jsonl} when finished.
     */
    @Override
    public String outputDir()
    {
        return this.outputDir;
    }

    @Override
    public String filePrefix()
    {
        return Optional.ofNullable(this.filePrefix).orElse("r7");
    }

    @Override
    public DataSize maxFileSize()
    {
        return Optional.ofNullable(this.maxFileSize).orElse(DataSize.ofMegabytes(256));
    }

    @Override
    public Duration maxFileAge()
    {
        return Optional.ofNullable(this.maxFileAge).orElse(Duration.ofMinutes(15));
    }

    @Override
    public Boolean prettyPrint()
    {
        return Optional.ofNullable(this.prettyPrint).orElse(false);
    }

    /**
     * Omitting {@code null}/empty fields (checksums that were never recorded, absent bodies,
     * headers not journaled, ...) cuts the typical line size dramatically for high-traffic
     * routes; defaults to {@code true} since most consumers only care about populated fields
     * and can tolerate a field being absent rather than explicitly {@code null}.
     */
    public Boolean hideEmptyFields()
    {
        return Optional.ofNullable(this.hideEmptyFields).orElse(true);
    }

    @Override
    public Duration pollInterval()
    {
        return Optional.ofNullable(this.pollInterval).orElse(Duration.ofSeconds(1));
    }

    @Override
    public void validate(final ValidationResult result)
    {
        if (this.outputDir != null && this.outputPath != null)
        {
            result.addError("output_dir", "cannot be combined with output_path: set one, for rotating files or a single stream");
        }
        if (this.outputDir != null && this.prettyPrint())
        {
            // A pretty-printed record spans lines, and after a crash a rotating file is cut
            // back to its last complete line: that could keep half a record.
            result.addError("pretty_print", "cannot be combined with output_dir");
        }
        final long maxFileSizeBytes = this.maxFileSize().bytes();
        if (maxFileSizeBytes < RollingFileOutputStream.MIN_ROLLOVER_SIZE)
        {
            result.addError("max_file_size", "must be at least " + RollingFileOutputStream.MIN_ROLLOVER_SIZE
                    + " bytes, but was " + maxFileSizeBytes);
        }
        if (this.maxFileAge().isNegative() || this.maxFileAge().isZero())
        {
            result.addError("max_file_age", "must be positive, but was " + this.maxFileAge());
        }
    }
}
