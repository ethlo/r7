package com.ethlo.r7.tailer.warc;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.journal.api.JournalIntegrityListener;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.r7f.R7Tailer;
import com.ethlo.r7.tailer.EnvConfig;
import com.ethlo.r7.warc.PayloadDedupIndex;
import com.ethlo.r7.warc.WarcExchangeWriter;
import com.ethlo.r7.warc.WarcFileWriter;

/**
 * Entry point for the standalone WARC/zstd tailer image (see {@code docs/journaling.md}).
 * <p>
 * Reads binary journals written by the gateway from {@code JOURNAL_DIR} and writes them as
 * {@code .warc.zst} files (WARC 1.1, one independent Zstandard frame per record) to
 * {@code OUTPUT_DIR}, rotating by size.
 */
public final class WarcTailerMain
{
    private static final Logger logger = LoggerFactory.getLogger(WarcTailerMain.class);

    private WarcTailerMain()
    {
    }

    public static void main(final String[] args) throws Exception
    {
        final Map<String, String> env = System.getenv();

        final Path journalDir = Paths.get(env.getOrDefault("JOURNAL_DIR", "/journals"));
        final Path outputDir = Paths.get(env.getOrDefault("OUTPUT_DIR", "/warc"));
        // Not under JOURNAL_DIR: a secondary tailer (one not responsible for deletion, see
        // R7Tailer's ttl/gracePeriod semantics) must be free to mount JOURNAL_DIR read-only,
        // which a checkpoint file living inside it would rule out. OUTPUT_DIR is always a
        // real, already-writable directory for this tailer, so it is a safe default parent.
        final Path checkpointDir = Paths.get(env.getOrDefault("CHECKPOINT_DIR", outputDir.resolve(".checkpoints").toString()));
        final String filePrefix = env.getOrDefault("WARC_FILE_PREFIX", "r7");
        final long maxFileSizeBytes = EnvConfig.dataSizeBytes(env, "WARC_MAX_FILE_SIZE", "1gb");
        if (maxFileSizeBytes < WarcFileWriter.MIN_ROLLOVER_SIZE)
        {
            throw new IllegalArgumentException("WARC_MAX_FILE_SIZE must be at least " + WarcFileWriter.MIN_ROLLOVER_SIZE
                    + " bytes, but was '" + env.get("WARC_MAX_FILE_SIZE") + "'");
        }
        final Duration maxFileAge = EnvConfig.duration(env, "WARC_MAX_FILE_AGE", "15m");
        final int zstdLevel = Integer.parseInt(env.getOrDefault("ZSTD_LEVEL", "9"));
        final int dedupCacheEntries = Integer.parseInt(env.getOrDefault("DEDUP_CACHE_ENTRIES", "100000"));
        final String ttlText = env.get("TTL");
        final Duration ttl = ttlText != null ? EnvConfig.parseDuration(ttlText) : null;
        // ttl and gracePeriod are mutually exclusive (R7Tailer fails fast if both are set), so
        // the "1h" default below must not silently reappear once ttl is configured - only
        // apply it when GRACE_PERIOD was not left to fall back onto ttl instead.
        final Duration gracePeriod = ttl == null || env.containsKey("GRACE_PERIOD")
                ? EnvConfig.duration(env, "GRACE_PERIOD", "1h")
                : null;
        final Duration pollInterval = EnvConfig.duration(env, "POLL_INTERVAL", "1s");

        logger.info("Tailing journals from '{}' -> WARC files in '{}' (checkpoints in '{}', max file size {} bytes, max file age {}, "
                        + "zstd level {}, dedup cache {} entries, min age {}, ttl {}, poll every {})",
                journalDir, outputDir, checkpointDir, maxFileSizeBytes, maxFileAge, zstdLevel, dedupCacheEntries, gracePeriod,
                ttl != null ? ttl : "disabled", pollInterval);

        final WarcFileWriter warcFileWriter = new WarcFileWriter(outputDir, filePrefix, maxFileSizeBytes, maxFileAge.toMillis(), zstdLevel);
        final PayloadDedupIndex dedupIndex = new PayloadDedupIndex(dedupCacheEntries);
        final WarcExchangeWriter warcWriter = new WarcExchangeWriter(warcFileWriter, dedupIndex);
        final JournalIntegrityListener integrity = new LoggingIntegrityListener();
        final R7Tailer tailer = new R7Tailer(journalDir, checkpointDir, gracePeriod, ttl, warcWriter, integrity, ReassemblyOptions.DEFAULTS);

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
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
            Thread.sleep(pollInterval.toMillis());
        }
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
