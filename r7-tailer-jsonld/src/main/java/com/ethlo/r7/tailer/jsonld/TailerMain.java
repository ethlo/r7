package com.ethlo.r7.tailer.jsonld;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.config.YamlConfigSupport;
import com.ethlo.r7.json.DebugJsonWriter;
import com.ethlo.r7.journal.api.JournalIntegrityListener;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.r7f.R7Tailer;
import com.ethlo.r7.validation.ValidationResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Entry point for the standalone JSON tailer image (see {@code docs/journaling.md}).
 * <p>
 * Reads binary journals written by the gateway from {@code journal_dir} and streams one
 * JSON object per completed exchange to {@code output_path}, which defaults to standard
 * output so it can be picked up by whatever log forwarder the deployment already runs
 * (Promtail, Fluent Bit, Vector, a Docker logging driver, ...). Configured by {@code
 * jsonld-tailer.yaml} (path overridable via {@code JSONLD_TAILER_CONFIG}), the same YAML
 * conventions as the gateway's {@code routes.yaml}/{@code server.yaml}.
 */
public final class TailerMain
{
    private static final Logger logger = LoggerFactory.getLogger(TailerMain.class);

    private TailerMain()
    {
    }

    public static void main(final String[] args) throws Exception
    {
        final Path configFile = Paths.get(System.getenv().getOrDefault("JSONLD_TAILER_CONFIG", "config/jsonld-tailer.yaml"));
        final JsonldTailerConfig config = loadConfig(configFile);

        final Path journalDir = Paths.get(config.journalDir());
        final Path checkpointDir = Paths.get(config.checkpointDir());
        final String outputPath = config.outputPath();
        final boolean prettyPrint = config.prettyPrint();
        final Duration pollInterval = config.pollInterval();

        final boolean toStdOut = "-".equals(outputPath) || "stdout".equalsIgnoreCase(outputPath);
        final OutputStream out;
        if (toStdOut)
        {
            out = System.out;
        }
        else
        {
            final Path resolvedOutputPath = Paths.get(outputPath);
            if (resolvedOutputPath.getParent() != null)
            {
                Files.createDirectories(resolvedOutputPath.getParent());
            }
            out = new BufferedOutputStream(Files.newOutputStream(resolvedOutputPath,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        }

        logger.info("Tailing journals from '{}' -> '{}' (checkpoints in '{}', poll every {})",
                journalDir, toStdOut ? "stdout" : outputPath, checkpointDir, pollInterval);

        final DebugJsonWriter jsonWriter = new DebugJsonWriter(out, prettyPrint);
        final JournalIntegrityListener integrity = new LoggingIntegrityListener();
        // Retention is a dedicated reaper's job, applied uniformly across every format-specific
        // tailer sharing the journal directory - this tailer never deletes a segment itself.
        final R7Tailer tailer = new R7Tailer(journalDir, checkpointDir, jsonWriter, integrity, ReassemblyOptions.DEFAULTS);

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            try
            {
                out.flush();
            }
            catch (IOException e)
            {
                logger.warn("Failed to flush output on shutdown", e);
            }
        }, "tailer-shutdown"));

        while (!Thread.currentThread().isInterrupted())
        {
            try
            {
                tailer.runTick();
            }
            catch (IOException e)
            {
                logger.error("Error while tailing journals in {}", journalDir, e);
            }
            Thread.sleep(pollInterval.toMillis());
        }
    }

    private static JsonldTailerConfig loadConfig(final Path configFile)
    {
        if (!Files.exists(configFile))
        {
            logger.info("No jsonld-tailer.yaml file found at {}. Using defaults", configFile.toAbsolutePath());
            return JsonldTailerConfig.standard();
        }

        logger.info("Loading JSON tailer settings from {}", configFile.toAbsolutePath());
        final ObjectMapper mapper = YamlConfigSupport.baseMapperBuilder().build();
        JsonldTailerConfig config = YamlConfigSupport.load(mapper, configFile, JsonldTailerConfig.class);
        if (config == null)
        {
            logger.warn("No settings found in jsonld-tailer.yaml, using only defaults");
            config = JsonldTailerConfig.standard();
        }
        final ValidationResult result = new ValidationResult();
        config.validate(result);
        result.throwIfInvalid();
        return config;
    }

    /**
     * Surfaces journal damage as log lines. Without this, a {@link R7Tailer} constructed
     * with the no-integrity-listener constructor silently discards these events, and an
     * operator has no way to learn that a segment lost entries.
     */
    private static final class LoggingIntegrityListener implements JournalIntegrityListener
    {
        @Override
        public void onEntriesMissing(final String segment, final long offset, final int expectedSequence, final int foundSequence, final int missingCount)
        {
            logger.warn("Journal '{}' is missing {} entr{} at offset {} (expected sequence {}, found {})",
                    segment, missingCount, missingCount == 1 ? "y" : "ies", offset, expectedSequence, foundSequence);
        }

        @Override
        public void onCorruptRegion(final String segment, final long offset, final long bytesSkipped, final String reason)
        {
            logger.warn("Journal '{}' has a corrupt region at offset {} ({} bytes skipped): {}", segment, offset, bytesSkipped, reason);
        }

        @Override
        public void onSequenceRegression(final String segment, final long offset, final int expectedSequence, final int foundSequence)
        {
            logger.warn("Journal '{}' has a sequence regression at offset {} (expected {}, found {})", segment, offset, expectedSequence, foundSequence);
        }

        @Override
        public void onDeltaUnreconstructable(final String requestId, final String part, final String reason)
        {
            logger.warn("Could not reconstruct {} headers for request '{}': {}", part, requestId, reason);
        }

        @Override
        public void onSegmentQuarantined(final String segment, final String reason)
        {
            logger.error("Journal segment '{}' was quarantined: {}", segment, reason);
        }

        @Override
        public void onDeliveryStalled(final String segment, final long offset, final int sequence, final Throwable cause)
        {
            logger.error("Delivery stalled on journal '{}' at offset {} (sequence {}); will retry", segment, offset, sequence, cause);
        }

        @Override
        public void onSegmentRecovered(final String segment, final long dataEnd, final long discardedBytes, final long recordsRecovered)
        {
            logger.info("Recovered journal '{}': {} record(s), data ends at {}, {} byte(s) discarded",
                    segment, recordsRecovered, dataEnd, discardedBytes);
        }
    }
}
