package com.ethlo.r7.tailer.warc;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.journal.api.JournalIntegrityListener;
import com.ethlo.r7.r7f.R7Tailer;
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
        final String filePrefix = env.getOrDefault("WARC_FILE_PREFIX", "r7");
        final long maxFileSizeBytes = Long.parseLong(env.getOrDefault("WARC_MAX_FILE_SIZE_BYTES", Long.toString(1_000_000_000L)));
        if (maxFileSizeBytes < WarcFileWriter.MIN_ROLLOVER_SIZE)
        {
            throw new IllegalArgumentException("WARC_MAX_FILE_SIZE_BYTES must be at least " + WarcFileWriter.MIN_ROLLOVER_SIZE
                    + " bytes, but was '" + env.get("WARC_MAX_FILE_SIZE_BYTES") + "'");
        }
        final long maxFileAgeSeconds = Long.parseLong(env.getOrDefault("WARC_MAX_FILE_AGE_SECONDS", "900"));
        if (maxFileAgeSeconds <= 0)
        {
            throw new IllegalArgumentException("WARC_MAX_FILE_AGE_SECONDS must be a positive number of seconds, but was '"
                    + env.get("WARC_MAX_FILE_AGE_SECONDS") + "'");
        }
        final int zstdLevel = Integer.parseInt(env.getOrDefault("ZSTD_LEVEL", "9"));
        final int dedupCacheEntries = Integer.parseInt(env.getOrDefault("DEDUP_CACHE_ENTRIES", "100000"));
        final Duration minAge = Duration.ofSeconds(Long.parseLong(env.getOrDefault("MIN_AGE_SECONDS", "3600")));
        final Duration pollInterval = Duration.ofMillis(Long.parseLong(env.getOrDefault("POLL_INTERVAL_MS", "1000")));

        logger.info("Tailing journals from '{}' -> WARC files in '{}' (max file size {} bytes, max file age {}s, zstd level {}, "
                        + "dedup cache {} entries, min age {}, poll every {})",
                journalDir, outputDir, maxFileSizeBytes, maxFileAgeSeconds, zstdLevel, dedupCacheEntries, minAge, pollInterval);

        final WarcFileWriter warcFileWriter = new WarcFileWriter(outputDir, filePrefix, maxFileSizeBytes, maxFileAgeSeconds * 1000L, zstdLevel);
        final PayloadDedupIndex dedupIndex = new PayloadDedupIndex(dedupCacheEntries);
        final WarcExchangeWriter warcWriter = new WarcExchangeWriter(warcFileWriter, dedupIndex);
        final JournalIntegrityListener integrity = new LoggingIntegrityListener();
        final R7Tailer tailer = new R7Tailer(journalDir, minAge, warcWriter, integrity);

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
