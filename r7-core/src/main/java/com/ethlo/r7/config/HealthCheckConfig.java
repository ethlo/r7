package com.ethlo.r7.config;

/**
 * Configuration for background health probes
 *
 * @param path     The endpoint to check (e.g., /health)
 * @param interval The duration between checks (e.g., 5s)
 */
public record HealthCheckConfig(
        String path,
        String interval
)
{
}