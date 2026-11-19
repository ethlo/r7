package com.ethlo.r7.tailer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.journal.api.JournalIntegrityListener;
import com.ethlo.r7.r7f.R7Tailer;

/**
 * Surfaces journal damage as log lines. Without a listener, {@link R7Tailer} discards these
 * events, and an operator has no way to learn that a segment lost entries.
 */
final class LoggingIntegrityListener implements JournalIntegrityListener
{
    private static final Logger logger = LoggerFactory.getLogger(LoggingIntegrityListener.class);

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
