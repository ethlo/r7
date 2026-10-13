package com.ethlo.r7.filters;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.UpstreamOutcome;

class CircuitBreakerOutcomeTest
{
    private static final Duration LONG = Duration.ofHours(1);

    private static CircuitBreakerKit.Breaker tripAfterOne()
    {
        return CircuitBreakerKit.breaker(1, LONG, null);
    }

    @Test
    void anUpstream5xxIsAFailure()
    {
        final CircuitBreakerKit.Breaker breaker = tripAfterOne();
        breaker.complete(CircuitBreakerKit.answered(503));
        assertThat(breaker.health()).isEqualTo("ERROR");
    }

    @Test
    void noResponseAndABrokenBodyAreFailures()
    {
        for (final UpstreamOutcome.Kind kind : new UpstreamOutcome.Kind[]{UpstreamOutcome.Kind.NO_RESPONSE, UpstreamOutcome.Kind.BODY_FAILED})
        {
            final CircuitBreakerKit.Breaker breaker = tripAfterOne();
            breaker.complete(CircuitBreakerKit.of(kind));
            assertThat(breaker.health()).as(kind.name()).isEqualTo("ERROR");
        }
    }

    @Test
    void aResponseR7MadeWithoutAskingTheUpstreamProvesNothing()
    {
        final CircuitBreakerKit.Breaker breaker = tripAfterOne();
        breaker.complete(CircuitBreakerKit.of(UpstreamOutcome.Kind.NOT_ASKED));
        assertThat(breaker.health()).isEqualTo("OK");
    }

    @Test
    void aClientThatLeavesIsNeitherAndDoesNotResetTheCount()
    {
        final CircuitBreakerKit.Breaker breaker = CircuitBreakerKit.breaker(2, LONG, null);
        breaker.complete(CircuitBreakerKit.answered(500));
        breaker.complete(CircuitBreakerKit.of(UpstreamOutcome.Kind.CLIENT_ABORTED));
        assertThat(breaker.reporting().status().values()).containsEntry("consecutive_failures", 1L);
        breaker.complete(CircuitBreakerKit.answered(500));
        assertThat(breaker.health()).isEqualTo("ERROR");
    }

    @Test
    void aSuccessResetsTheCount()
    {
        final CircuitBreakerKit.Breaker breaker = CircuitBreakerKit.breaker(2, LONG, null);
        breaker.complete(CircuitBreakerKit.answered(500));
        breaker.complete(CircuitBreakerKit.answered(200));
        assertThat(breaker.reporting().status().values()).containsEntry("consecutive_failures", 0L);
    }

    @Test
    void aFallbackIsAFailureCountedOnce()
    {
        final CircuitBreakerKit.Breaker breaker = CircuitBreakerKit.breaker(2, LONG, null);
        final CircuitBreakerKit.Exchange exchange = CircuitBreakerKit.answered(503);
        breaker.fallBack(exchange);
        breaker.complete(exchange);
        assertThat(breaker.reporting().status().values()).containsEntry("consecutive_failures", 1L);
    }

    @Test
    void aSlowAnswerIsAFailureEvenWhenItIsA200()
    {
        final CircuitBreakerKit.Breaker breaker = CircuitBreakerKit.breaker(1, LONG, Duration.ofMillis(100));
        breaker.complete(CircuitBreakerKit.answeredAfter(Duration.ofMillis(50), 200));
        assertThat(breaker.health()).isEqualTo("OK");
        breaker.complete(CircuitBreakerKit.answeredAfter(Duration.ofMillis(500), 200));
        assertThat(breaker.health()).isEqualTo("ERROR");
    }
}
