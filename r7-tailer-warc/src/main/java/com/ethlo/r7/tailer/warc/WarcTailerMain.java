package com.ethlo.r7.tailer.warc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.config.YamlConfigSupport;
import com.ethlo.r7.journal.api.JournalIntegrityListener;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.r7f.R7Tailer;
import com.ethlo.r7.validation.ValidationResult;
import com.ethlo.r7.warc.PayloadDedupIndex;
import com.ethlo.r7.warc.WarcExchangeWriter;
import com.ethlo.r7.warc.WarcFileWriter;
import tools.jackson.databind.ObjectMapper;

/**
 * Entry point for the standalone WARC/zstd tailer image (see {@code docs/journaling.md}).
 * <p>
 * Reads binary journals written by the gateway from {@code journal_dir} and writes them as
 * {@code .warc.zst} files (WARC 1.1, one independent Zstandard frame per record) to
 * {@code output_dir}, rotating by size. Configured by {@code warc-tailer.yaml} (path
 * overridable via {@code WARC_TAILER_CONFIG}), the same YAML conventions as the gateway's
 * {@code routes.yaml}/{@code server.yaml}.
 */
public final class WarcTailerMain
{
    private static final Logger logger = LoggerFactory.getLogger(WarcTailerMain.class);

    private WarcTailerMain()
    {
    }

    public static void main(final String[] args) throws Exception
    {
        final Path configFile = Paths.get(System.getenv().getOrDefault("WARC_TAILER_CONFIG", "config/warc-tailer.yaml"));
        final WarcTailerConfig config = loadConfig(configFile);

        final Path journalDir = Paths.get(config.journalDir());
        final Path outputDir = Paths.get(config.outputDir());
        final Path checkpointDir = Paths.get(config.checkpointDir());
        final String filePrefix = config.filePrefix();
        final long maxFileSizeBytes = config.maxFileSize().bytes();
        final Duration maxFileAge = config.maxFileAge();
        final int zstdLevel = config.zstdLevel();
        final int dedupCacheEntries = config.dedupCacheEntries();
        final Duration pollInterval = config.pollInterval();

        logger.info("Tailing journals from '{}' -> WARC files in '{}' (checkpoints in '{}', max file size {} bytes, max file age {}, "
                        + "zstd level {}, dedup cache {} entries, poll every {})",
                journalDir, outputDir, checkpointDir, maxFileSizeBytes, maxFileAge, zstdLevel, dedupCacheEntries, pollInterval);

        final WarcFileWriter warcFileWriter = new WarcFileWriter(outputDir, filePrefix, maxFileSizeBytes, maxFileAge.toMillis(), zstdLevel);
        final PayloadDedupIndex dedupIndex = new PayloadDedupIndex(dedupCacheEntries);
        final WarcExchangeWriter warcWriter = new WarcExchangeWriter(warcFileWriter, dedupIndex);
        final JournalIntegrityListener integrity = new LoggingIntegrityListener();
        // Retention is a dedicated reaper's job, applied uniformly across every format-specific
        // tailer sharing the journal directory - this tailer never deletes a segment itself.
        final R7Tailer tailer = new R7Tailer(journalDir, checkpointDir, warcWriter, integrity, ReassemblyOptions.DEFAULTS);

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            // SIGTERM (a container restart, a rolling deploy) saves the checkpoint, so the next
            // run resumes from where reading got to rather than from the last tick; it must run
            // before the WARC file is closed. Exchanges still being assembled are completed
            // after the restart either way, as after a hard crash.
            tailer.shutdown();
            try
            {
                warcFileWriter.close();
            }
            catch (IOException e)
            {
                logger.warn("Failed to close current WARC file on shutdown", e);
            }
        }, "warc-tailer-shutdown"));

        while (!Thread.currentThread().isInterrupted())
        {
            try
            {
                tailer.runTick();
            }
            catch (final IOException | RuntimeException e)
            {
                // WarcExchangeWriter reports a delivery failure as an unchecked
                // UncheckedIOException (ExchangeCompletionListener.onComplete cannot declare a
                // checked one) - R7Tailer deliberately re-offers that entry on the next tick, so
                // catching only IOException here would let that same failure kill the process
                // instead of reaching the retry it was designed for.
                logger.error("Error while tailing journals in {}", journalDir, e);
            }
            // Returns as soon as the gateway commits something, or after poll_interval.
            tailer.awaitNewData(pollInterval);
        }
    }

    private static WarcTailerConfig loadConfig(final Path configFile)
    {
        if (!Files.exists(configFile))
        {
            logger.info("No warc-tailer.yaml file found at {}. Using defaults", configFile.toAbsolutePath());
            return WarcTailerConfig.standard();
        }

        logger.info("Loading WARC tailer settings from {}", configFile.toAbsolutePath());
        final ObjectMapper mapper = YamlConfigSupport.baseMapperBuilder().build();
        WarcTailerConfig config = YamlConfigSupport.load(mapper, configFile, WarcTailerConfig.class);
        if (config == null)
        {
            logger.warn("No settings found in warc-tailer.yaml, using only defaults");
            config = WarcTailerConfig.standard();
        }
        final ValidationResult result = new ValidationResult();
        config.validate(result);
        result.throwIfInvalid();
        return config;
    }

    /**
     * Surfaces journal damage as log lines; see the identical listener in the JSON tailer for
     * why this is needed at all (without it, {@link R7Tailer} silently discards these events).
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
