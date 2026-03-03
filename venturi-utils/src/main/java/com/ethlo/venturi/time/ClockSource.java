package com.ethlo.venturi.time;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class ClockSource
{
    /**
     * Returns the current nanoseconds since the Unix Epoch.
     */
    public static long now()
    {
        final Instant now = Instant.now();
        return (now.getEpochSecond() * 1_000_000_000L) + now.getNano();
    }

    public static OffsetDateTime convertToUtc(final long epochNanos)
    {
        final long seconds = epochNanos / 1_000_000_000L;
        final long nanoAdjustment = epochNanos % 1_000_000_000L;
        final Instant instant = Instant.ofEpochSecond(seconds, nanoAdjustment);
        return instant.atOffset(ZoneOffset.UTC);
    }
}