package com.ethlo.r7.filters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.ComponentStatus;
import com.ethlo.r7.api.MutableGatewayResponse;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.StatusReporting;

class CircuitBreakerStatusTest
{
    private static ClientResponseGatewayExchange responded(final int status)
    {
        final MutableGatewayResponse response = mock(MutableGatewayResponse.class);
        when(response.status()).thenReturn(status);
        final ClientResponseGatewayExchange exchange = mock(ClientResponseGatewayExchange.class);
        when(exchange.clientResponse()).thenReturn(response);
        return exchange;
    }

    @Test
    void anOpenCircuitIsAnErrorAndCountsWhatItRejects()
    {
        final Object breaker = new CircuitBreakerFactory().create(new CircuitBreakerFactory.Config(2, Duration.ofHours(1)), null);
        final StatusReporting reporting = (StatusReporting) breaker;

        assertThat(reporting.status().health()).isEqualTo(ComponentStatus.Health.OK);

        ((ClientResponseGatewayFilter) breaker).onClientResponse(responded(502));
        assertThat(reporting.status().health()).as("one failure below the threshold").isEqualTo(ComponentStatus.Health.OK);
        assertThat(reporting.status().values()).containsEntry("consecutive_failures", 1L);

        ((ClientResponseGatewayFilter) breaker).onClientResponse(responded(503));
        final ComponentStatus open = reporting.status();
        assertThat(open.health()).isEqualTo(ComponentStatus.Health.ERROR);
        assertThat(open.detail()).startsWith("Open since ");

        ((ClientRequestGatewayFilter) breaker).onClientRequest(mock(ClientRequestGatewayExchange.class));
        assertThat(reporting.status().values()).containsEntry("rejected_requests", 1L);
    }

    /**
     * The summary describes the configuration; the state is the status's to report, so the
     * summary does not change from one request to the next.
     */
    @Test
    void theSummaryNoLongerCarriesTheState()
    {
        final Object breaker = new CircuitBreakerFactory().create(new CircuitBreakerFactory.Config(1, Duration.ofHours(1)), null);
        ((ClientResponseGatewayFilter) breaker).onClientResponse(responded(500));
        assertThat(((ShortInfo) breaker).summary()).doesNotContain("state=").contains("failure_threshold=1");
    }
}
