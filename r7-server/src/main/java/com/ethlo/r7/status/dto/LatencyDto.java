package com.ethlo.r7.status.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response time over the route's sparkline window. Percentiles are bucket upper bounds in
 * microseconds (see {@link com.ethlo.r7.status.LatencyHistogram}), 0 when the window is empty.
 *
 * @param p99SeriesMicros one p99 per sparkline interval, oldest first, aligned with the sparkline arrays
 */
public record LatencyDto(
        @JsonProperty("window_samples") long windowSamples,
        @JsonProperty("p50_micros") long p50Micros,
        @JsonProperty("p95_micros") long p95Micros,
        @JsonProperty("p99_micros") long p99Micros,
        @JsonProperty("p99_series_micros") long[] p99SeriesMicros
)
{
}
