package com.ethlo.r7.tailer.files;

import java.time.Duration;

import com.ethlo.r7.config.model.DataSize;
import com.ethlo.r7.validation.ValidationResult;

/**
 * The settings of a tailer that writes local files: where, under what name, and when a file is
 * sealed. Only tailers with file output implement this; the reader settings every tailer has
 * are in {@code TailerConfig}. Defaults stay with each tailer, since a sensible size differs by
 * format.
 */
public interface RollingFilesConfig
{
    String DEFAULT_FILE_PREFIX = "r7";

    Duration DEFAULT_MAX_FILE_AGE = Duration.ofMinutes(15);

    String outputDir();

    String filePrefix();

    DataSize maxFileSize();

    Duration maxFileAge();

    /**
     * The checks {@link SealedFileWriter} would otherwise fail at construction, named by field.
     */
    default void validateRollover(final ValidationResult result)
    {
        final long maxFileSizeBytes = this.maxFileSize().bytes();
        if (maxFileSizeBytes < SealedFileWriter.MIN_ROLLOVER_SIZE)
        {
            result.addError("max_file_size", "must be at least " + SealedFileWriter.MIN_ROLLOVER_SIZE
                    + " bytes, but was " + maxFileSizeBytes);
        }
        if (this.maxFileAge().isNegative() || this.maxFileAge().isZero())
        {
            result.addError("max_file_age", "must be positive, but was " + this.maxFileAge());
        }
    }
}
