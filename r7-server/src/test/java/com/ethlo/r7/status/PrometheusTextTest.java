package com.ethlo.r7.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.ComponentStatus;
import com.ethlo.r7.status.dto.FilterNode;
import com.ethlo.r7.status.dto.RouteConfigDto;

class PrometheusTextTest
{
    private static List<String> lines(final PrometheusText text)
    {
        return Arrays.asList(new String(text.toBytes(), StandardCharsets.UTF_8).split("\n"));
    }

    /**
     * A route id or a filter's name is the operator's text: a quote or a line feed in it must not
     * end the label value, or start a sample of its own.
     */
    @Test
    void labelValuesAreEscaped()
    {
        final PrometheusText text = new PrometheusText()
                .metric("r7_x", "gauge", "Help.")
                .sample("r7_x", 1, "route", "a\"b\\c\nr7_forged 1");

        assertThat(lines(text)).containsExactly(
                "# HELP r7_x Help.",
                "# TYPE r7_x gauge",
                "r7_x{route=\"a\\\"b\\\\c\\nr7_forged 1\"} 1");
    }

    @Test
    void aMetricIsDeclaredOnce()
    {
        final PrometheusText text = new PrometheusText().metric("r7_x", "gauge", "Help.");
        assertThatThrownBy(() -> text.metric("r7_x", "gauge", "Help.")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decimalsAreWrittenPlain()
    {
        final PrometheusText text = new PrometheusText().metric("r7_s", "counter", "Help.").sample("r7_s", BigDecimal.valueOf(1, 9));
        assertThat(lines(text)).contains("r7_s 0.000000001");
    }

    /**
     * Prometheus buckets are cumulative and each bound must cover whole histogram buckets: a
     * response of 300µs is under 512µs and every bound above, never under 256µs.
     */
    @Test
    void theLatencyHistogramIsCumulativeOverBucketBounds()
    {
        final LatencyWindow window = new LatencyWindow(4);
        window.record(300_000L);
        window.record(300_000L);
        window.record(3_000_000L);
        window.record(60_000_000_000L);

        final PrometheusText text = new PrometheusText().metric("r7_route_request_duration_seconds", "histogram", "Help.");
        ManagementEndpoint.latencyHistogram(text, "r", window.cumulativeCounts(), window.cumulativeNanos());

        assertThat(lines(text)).contains(
                "r7_route_request_duration_seconds_bucket{route=\"r\",le=\"0.000256\"} 0",
                "r7_route_request_duration_seconds_bucket{route=\"r\",le=\"0.000512\"} 2",
                "r7_route_request_duration_seconds_bucket{route=\"r\",le=\"0.002048\"} 2",
                "r7_route_request_duration_seconds_bucket{route=\"r\",le=\"0.004096\"} 3",
                // A minute is past the histogram's last bound: only +Inf counts it
                "r7_route_request_duration_seconds_bucket{route=\"r\",le=\"16.777216\"} 3",
                "r7_route_request_duration_seconds_bucket{route=\"r\",le=\"+Inf\"} 4",
                "r7_route_request_duration_seconds_sum{route=\"r\"} 60.003600000",
                "r7_route_request_duration_seconds_count{route=\"r\"} 4");
    }

    /**
     * A global filter is one instance in front of every route: its counts are gateway-wide, so
     * it is reported once without a route, or summing over routes would multiply them.
     */
    @Test
    void aGlobalFilterIsReportedOnceWithoutARoute()
    {
        final ComponentStatus shared = new ComponentStatus(ComponentStatus.Health.WARN, null, Map.of("rejected_requests", 7L));
        final ComponentStatus own = new ComponentStatus(ComponentStatus.Health.ERROR, null, null);
        final List<RouteConfigDto> routes = List.of(route("a", shared, own), route("b", shared, null));

        final PrometheusText text = new PrometheusText();
        ManagementEndpoint.components(text, routes);

        assertThat(lines(text)).containsSubsequence(
                "r7_component_health{route=\"\",component=\"RateLimiter\",position=\"1\"} 1",
                "r7_component_health{route=\"a\",component=\"CircuitBreaker\",position=\"2\"} 2",
                "r7_component_value{route=\"\",component=\"RateLimiter\",position=\"1\",name=\"rejected_requests\"} 7");
        assertThat(lines(text).stream().filter(l -> l.contains("RateLimiter")).count()).isEqualTo(2);
    }

    private static RouteConfigDto route(final String id, final ComponentStatus global, final ComponentStatus own)
    {
        final FilterNode upstream = new FilterNode("upstream", "None", false, false, false, false, false, null, null);
        final FilterNode breaker = new FilterNode("CircuitBreaker", "", false, true, false, true, false, own, upstream);
        final FilterNode limiter = new FilterNode("RateLimiter", "", true, true, false, true, false, global, breaker);
        return new RouteConfigDto(id, 1, null, null, null, null, List.of(), limiter);
    }
}
