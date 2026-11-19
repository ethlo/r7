package com.ethlo.r7.tailer.app;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import com.ethlo.r7.config.model.DataSize;
import com.ethlo.r7.tailer.files.RollingFilesConfig;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The {@code json} block of {@code tailer.yaml}: one JSON object per exchange, one per line, to
 * standard output or to files rolled on size or age. The file settings apply only to
 * {@code output: file}; {@code body_files} applies only to {@code bodies: files}.
 */
public record JsonOutputConfig(
        Boolean enabled,
        Output output,
        String outputDir,
        String filePrefix,
        DataSize maxFileSize,
        Duration maxFileAge,
        Bodies bodies,
        BodyFilesConfig bodyFiles
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

    /**
     * Where a captured body goes when no WARC record stores it.
     */
    public enum Bodies
    {
        /**
         * Nowhere: payloads stay out of the log, and the sizes and checksums still show there
         * was a body.
         */
        @JsonProperty("none")
        NONE,

        /**
         * Into the line, base64 encoded.
         */
        @JsonProperty("inline")
        INLINE,

        /**
         * Into body files ({@code body_files}), one Zstandard frame per body; the line points at
         * each with a {@code body_ref}.
         */
        @JsonProperty("files")
        FILES;

        /**
         * Also reads the booleans this setting used to take, to refuse them with what replaced
         * them rather than a type error.
         */
        @JsonCreator
        static Bodies of(final String value)
        {
            for (final Bodies bodies : values())
            {
                if (bodies.name().toLowerCase(Locale.ROOT).equals(value))
                {
                    return bodies;
                }
            }
            final String hint = switch (value)
            {
                case "true" -> "; 'true' is now 'inline'";
                case "false" -> "; 'false' is now 'none'";
                default -> "";
            };
            throw new IllegalArgumentException("must be none, inline or files, but was '" + value + "'" + hint);
        }
    }

    public static JsonOutputConfig standard()
    {
        return new JsonOutputConfig(null, null, null, null, null, null, null, null);
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

    /**
     * Where the captured bodies no WARC file holds go. None by default, for a log that should
     * never hold payloads.
     */
    public Bodies bodies()
    {
        return Optional.ofNullable(this.bodies).orElse(Bodies.NONE);
    }

    public BodyFilesConfig bodyFiles()
    {
        return Optional.ofNullable(this.bodyFiles).orElseGet(BodyFilesConfig::standard);
    }

    @Override
    public void validate(final ValidationResult result)
    {
        if (this.output() == Output.STDOUT
                && (this.outputDir != null || this.filePrefix != null || this.maxFileSize != null || this.maxFileAge != null))
        {
            result.addError("output", "output_dir, file_prefix, max_file_size and max_file_age apply only to output: file");
        }
        this.validateRollover(result);
        if (this.bodyFiles != null && this.bodies() != Bodies.FILES)
        {
            result.addError("body_files", "applies only to bodies: files");
        }
        this.bodyFiles().validate(result.nested("body_files"));
    }
}
