package com.ethlo.r7.status;

import java.net.URI;
import java.util.Map;

public interface UpstreamHealthMonitor
{
    boolean hasAvailableTargets();

    void stop();

    /**
     * Whether each target is currently in rotation, for display. Empty when targets are not
     * health-checked, since then nothing is known about them beyond being configured.
     */
    default Map<URI, Boolean> targetStates()
    {
        return Map.of();
    }
}
