package com.ethlo.r7.filters;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.CompletedGatewayExchange;
import com.ethlo.r7.api.CompletedGatewayFilter;
import com.ethlo.r7.api.FallbackAwareGatewayFilter;
import com.ethlo.r7.api.GatewayResponse;
import com.ethlo.r7.api.StateKey;
import com.ethlo.r7.api.StatusReporting;
import com.ethlo.r7.api.UpstreamOutcome;

/**
 * An exchange that is a client request, a fallback and a completed exchange at once, with real
 * attachments, so the breaker's per-exchange marks behave as they do in the server.
 */
final class CircuitBreakerKit
{
    private CircuitBreakerKit()
    {
    }

    interface Exchange extends ClientRequestGatewayExchange, CompletedGatewayExchange
    {
    }

    @SuppressWarnings("unchecked")
    static Exchange exchange(final UpstreamOutcome outcome, final int upstreamStatus)
    {
        final Map<StateKey<?>, Object> attachments = new HashMap<>();
        final Exchange exchange = mock(Exchange.class);
        org.mockito.Mockito.doAnswer(call ->
        {
            attachments.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(exchange).setAttachment(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        when(exchange.getAttachment(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> attachments.get(call.getArgument(0)));
        when(exchange.upstreamOutcome()).thenReturn(outcome);
        final GatewayResponse upstream = mock(GatewayResponse.class);
        when(upstream.status()).thenReturn(upstreamStatus);
        when(exchange.upstreamResponse()).thenReturn(upstream);
        return exchange;
    }

    static Exchange answered(final int status)
    {
        return exchange(new UpstreamOutcome(UpstreamOutcome.Kind.ANSWERED, 1_000_000), status);
    }

    static Exchange answeredAfter(final Duration head, final int status)
    {
        return exchange(new UpstreamOutcome(UpstreamOutcome.Kind.ANSWERED, head.toNanos()), status);
    }

    static Exchange of(final UpstreamOutcome.Kind kind)
    {
        return exchange(new UpstreamOutcome(kind, kind == UpstreamOutcome.Kind.NOT_ASKED || kind == UpstreamOutcome.Kind.NO_RESPONSE ? -1 : 1_000_000), 200);
    }

    static Breaker breaker(final int threshold, final Duration cooldown, final Duration slowCall)
    {
        return new Breaker(new CircuitBreakerFactory().create(new CircuitBreakerFactory.Config(threshold, cooldown, slowCall), null));
    }

    record Breaker(Object filter)
    {
        StatusReporting reporting()
        {
            return (StatusReporting) filter;
        }

        /**
         * Runs the request phase, then completes the exchange: the exchange's whole life as far as
         * the breaker can tell. Returns whether the breaker let the request through.
         */
        boolean through(final Exchange exchange)
        {
            ((ClientRequestGatewayFilter) filter).onClientRequest(exchange);
            return !org.mockito.Mockito.mockingDetails(exchange).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("shortCircuit"));
        }

        void complete(final Exchange exchange)
        {
            ((CompletedGatewayFilter) filter).onCompleted(exchange);
        }

        void fallBack(final Exchange exchange)
        {
            ((FallbackAwareGatewayFilter) filter).onFallback(exchange);
        }

        String health()
        {
            return reporting().status().health().name();
        }
    }
}
