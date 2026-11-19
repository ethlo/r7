package com.ethlo.r7.filters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.CompletedGatewayFilter;
import com.ethlo.r7.api.ComponentStatus;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.StatusReporting;

class CircuitBreakerStatusTest
{
    @Test
    void anOpenCircuitIsAnErrorAndCountsWhatItRejects()
    {
        final CircuitBreakerKit.Breaker breaker = CircuitBreakerKit.breaker(2, Duration.ofHours(1), null);
        final StatusReporting reporting = breaker.reporting();

        assertThat(reporting.status().health()).isEqualTo(ComponentStatus.Health.OK);

        breaker.complete(CircuitBreakerKit.answered(502));
        assertThat(reporting.status().health()).as("one failure below the threshold").isEqualTo(ComponentStatus.Health.OK);
        assertThat(reporting.status().values()).containsEntry("consecutive_failures", 1L);

        breaker.complete(CircuitBreakerKit.answered(503));
        final ComponentStatus open = reporting.status();
        assertThat(open.health()).isEqualTo(ComponentStatus.Health.ERROR);
        assertThat(open.detail()).startsWith("Open since ");

        ((ClientRequestGatewayFilter) breaker.filter()).onClientRequest(mock(ClientRequestGatewayExchange.class));
        assertThat(reporting.status().values()).containsEntry("rejected_requests", 1L);
    }

    /**
     * The summary describes the configuration; the state is the status's to report, so the
     * summary does not change from one request to the next.
     */
    @Test
    void theSummaryNoLongerCarriesTheState()
    {
        final CircuitBreakerKit.Breaker breaker = CircuitBreakerKit.breaker(1, Duration.ofHours(1), null);
        breaker.complete(CircuitBreakerKit.answered(500));
        assertThat(((ShortInfo) breaker.filter()).summary()).doesNotContain("state=").contains("failure_threshold=1");
    }

    @Test
    void theFilterIsJudgedAtCompletion()
    {
        final CircuitBreakerKit.Breaker breaker = CircuitBreakerKit.breaker(1, Duration.ofHours(1), null);
        assertThat(breaker.filter()).isInstanceOf(CompletedGatewayFilter.class);
    }
}
