package com.ethlo.r7.status.dto;

import java.time.Duration;
import java.util.List;

import com.ethlo.r7.config.HealthCheckConfig;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A route's upstream as configured, with credentials removed from the target URLs.
 *
 * @param healthCheck null when the route has no health check, in which case every target is
 *                    always considered up
 */
public record UpstreamDto(
        List<String> targets,
        @JsonProperty("read_timeout") Duration readTimeout,
        @JsonProperty("health_check") HealthCheckConfig healthCheck,
        @JsonProperty("fallback_route_id") String fallbackRouteId
)
{
}
