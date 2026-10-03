package com.ethlo.r7.tailer.app;

import java.time.Duration;
import java.util.Optional;

import com.ethlo.r7.config.model.DataSize;
import com.ethlo.r7.tailer.files.RollingFilesConfig;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The {@code json} block of {@code tailer.yaml}: one JSON object per exchange, one per line, to
 * standard output or to files rolled on size or age. The file settings apply only to
 * {@code output: file}.
 */
public record JsonOutputConfig(
        Boolean enabled,
        Output output,
        String outputDir,
        String filePrefix,
        DataSize maxFileSize,
        Duration maxFileAge,
        Boolean prettyPrint,
        Boolean hideEmptyFields,
        Boolean bodies
) implements RollingFilesConfig, ValidatableConfig
{
    public enum Output
    {
        /**
         * For whatever log forwarder the deployment already runs (Promtail, Fluent Bit, Vector,
         * a Docker logging driver).
         */
        @JsonProperty("stdout")
        STDOUT,

        /**
         * Files in {@code output_dir}, renamed from {@code .jsonl.open} to {@code .jsonl} when
         * finished.
         */
        @JsonProperty("file")
        FILE
    }

    public static JsonOutputConfig standard()
    {
        return new JsonOutputConfig(null, null, null, null, null, null, null, null, null);
    }

    public Boolean enabled()
    {
        return Optional.ofNullable(this.enabled).orElse(true);
    }

    public Output output()
    {
        return Optional.ofNullable(this.output).orElse(Output.STDOUT);
    }

    @Override
    public String outputDir()
    {
        return Optional.ofNullable(this.outputDir).orElse("/json");
    }

    @Override
    public String filePrefix()
    {
        return Optional.ofNullable(this.filePrefix).orElse(DEFAULT_FILE_PREFIX);
    }

    @Override
    public DataSize maxFileSize()
    {
        return Optional.ofNullable(this.maxFileSize).orElse(DataSize.ofMegabytes(256));
    }

    @Override
    public Duration maxFileAge()
    {
        return Optional.ofNullable(this.maxFileAge).orElse(DEFAULT_MAX_FILE_AGE);
    }

    public Boolean prettyPrint()
    {
        return Optional.ofNullable(this.prettyPrint).orElse(false);
    }

    /**
     * Omitting {@code null}/empty fields (checksums that were never recorded, absent bodies,
     * headers not journaled, ...) cuts the typical line size dramatically for high-traffic
     * routes.
     */
    public Boolean hideEmptyFields()
    {
        return Optional.ofNullable(this.hideEmptyFields).orElse(true);
    }

    /**
     * Whether a line carries the captured bodies when no WARC file holds them. Off for a log
     * that should never hold payloads, such as a rerun over journals that captured them.
     */
    public Boolean bodies()
    {
        return Optional.ofNullable(this.bodies).orElse(true);
    }

    @Override
    public void validate(final ValidationResult result)
    {
        if (this.output() == Output.FILE && this.prettyPrint())
        {
            // A pretty-printed record spans lines, and after a crash a file is cut back to its
            // last complete line: that could keep half a record.
            result.addError("pretty_print", "cannot be combined with output: file");
        }
        if (this.output() == Output.STDOUT
                && (this.outputDir != null || this.filePrefix != null || this.maxFileSize != null || this.maxFileAge != null))
        {
            result.addError("output", "output_dir, file_prefix, max_file_size and max_file_age apply only to output: file");
        }
        this.validateRollover(result);
    }
}
