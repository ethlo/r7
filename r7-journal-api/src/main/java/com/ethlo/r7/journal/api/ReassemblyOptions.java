package com.ethlo.r7.journal.api;

import java.time.Duration;

/**
 * Tuning for the reader that rebuilds exchanges from the interleaved event stream.
 * <p>
 * These settings trade memory against how long the reader is willing to wait for the
 * remaining events of an exchange. Both directions have consequences that are easy to
 * misread, so see {@code r7-journal-mmap/README.md} §11 before changing them.
 *
 * @param maxAge              how long an exchange may be held by the reader without its
 *                            EndExchange before being abandoned. Note that this measures
 *                            <em>reader-side retention</em>, not request duration: the
 *                            clock starts when the reader first sees an event for the
 *                            exchange, not when the request began. See
 *                            {@code README.md} §11.1.
 * @param maxInFlight         hard ceiling on concurrently tracked exchanges. A backstop
 *                            against heap exhaustion, not a tuning knob — reaching it
 *                            force-evicts the whole tracked set, including exchanges that
 *                            would have completed moments later. See §11.2.
 * @param sweepIntervalEvents how many observed events between age sweeps. Eviction is
 *                            amortised, so an exchange can exceed {@code maxAge} by up to
 *                            one sweep before it is noticed. See §11.3.
 */
public record ReassemblyOptions(Duration maxAge, int maxInFlight, int sweepIntervalEvents)
{
    /**
     * Five minutes of reader-side retention, a quarter-million tracked exchanges, and a
     * sweep every 8192 events. Suitable for tailing a live stream with ordinary request
     * durations; see §11.1 for when it is not.
     */
    public static final ReassemblyOptions DEFAULTS = new ReassemblyOptions(Duration.ofMinutes(5), 250_000, 8192);

    public ReassemblyOptions
    {
        if (maxAge == null || maxAge.isNegative() || maxAge.isZero())
        {
            throw new IllegalArgumentException("maxAge must be a positive duration, got " + maxAge);
        }
        if (maxInFlight < 1)
        {
            throw new IllegalArgumentException("maxInFlight must be at least 1, got " + maxInFlight);
        }
        if (sweepIntervalEvents < 1)
        {
            throw new IllegalArgumentException("sweepIntervalEvents must be at least 1, got " + sweepIntervalEvents);
        }
    }

    public ReassemblyOptions withMaxAge(final Duration maxAge)
    {
        return new ReassemblyOptions(maxAge, maxInFlight, sweepIntervalEvents);
    }

    public ReassemblyOptions withMaxInFlight(final int maxInFlight)
    {
        return new ReassemblyOptions(maxAge, maxInFlight, sweepIntervalEvents);
    }

    public ReassemblyOptions withSweepIntervalEvents(final int sweepIntervalEvents)
    {
        return new ReassemblyOptions(maxAge, maxInFlight, sweepIntervalEvents);
    }
}
