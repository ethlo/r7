package com.ethlo.venturi.util;

import java.time.Duration;
import java.time.Instant;

public class SystemUtil
{
    public static long getUptime()
    {
        Instant start = ProcessHandle.current().info().startInstant().orElse(Instant.now());
        Duration uptime = Duration.between(start, Instant.now());
        return uptime.toMillis();
    }
}
