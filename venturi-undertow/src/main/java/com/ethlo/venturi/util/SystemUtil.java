package com.ethlo.venturi.util;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;

public class SystemUtil
{
    private static final Instant startupTime = ProcessHandle.current().info().startInstant().orElse(Instant.now()).truncatedTo(ChronoUnit.MILLIS);

    private SystemUtil()
    {
    }

    public static Duration getUptime()
    {
        return Duration.between(getStartTime(), Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    public static Temporal getStartTime()
    {
        return startupTime;
    }
}
