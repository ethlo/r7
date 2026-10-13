package com.ethlo.r7.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.GatewayResponse;
import com.ethlo.r7.api.GatewayResponsePredicate;

class ConditionTest
{
    private static GatewayPredicate always()
    {
        final GatewayPredicate predicate = mock(GatewayPredicate.class);
        when(predicate.test(any())).thenReturn(true);
        when(predicate.summary()).thenReturn("Always");
        return predicate;
    }

    private static ConditionInput input(final GatewayRequest upstreamRequest, final GatewayResponse upstreamResponse, final double draw)
    {
        final GatewayRequest client = mock(GatewayRequest.class);
        return new ConditionInput()
        {
            @Override
            public GatewayRequest request(final Leg leg)
            {
                return leg == Leg.CLIENT ? client : upstreamRequest;
            }

            @Override
            public GatewayResponse response(final Leg leg)
            {
                return leg == Leg.CLIENT ? null : upstreamResponse;
            }

            @Override
            public double sampleDraw()
            {
                return draw;
            }
        };
    }

    @Test
    void anUpstreamLegPredicateIsFalseOnAnUnproxiedExchangeAndNotInvertsThat()
    {
        final GatewayPredicate predicate = always();
        final Condition upstream = new RequestCondition(predicate, Leg.UPSTREAM);
        final ConditionInput unproxied = input(null, null, 0.5);

        assertThat(upstream.test(unproxied)).isFalse();
        assertThat(new NotCondition(upstream).test(unproxied)).isTrue();
        verify(predicate, never()).test(any());

        assertThat(upstream.test(input(mock(GatewayRequest.class), null, 0.5))).isTrue();
    }

    @Test
    void anUpstreamResponsePredicateIsFalseUntilAHeadIsRead()
    {
        final GatewayResponsePredicate predicate = mock(GatewayResponsePredicate.class);
        when(predicate.test(any())).thenReturn(true);
        final Condition upstream = new ResponseCondition(predicate, Leg.UPSTREAM);

        assertThat(upstream.test(input(mock(GatewayRequest.class), null, 0.5))).isFalse();
        assertThat(upstream.test(input(mock(GatewayRequest.class), mock(GatewayResponse.class), 0.5))).isTrue();
    }

    @Test
    void andAndOrStopAtTheFirstAnswer()
    {
        final GatewayPredicate untouched = always();
        final Condition last = new RequestCondition(untouched, Leg.CLIENT);
        final ConditionInput input = input(null, null, 0.5);

        assertThat(new AndCondition(List.of(Condition.FALSE, last)).test(input)).isFalse();
        assertThat(new OrCondition(List.of(Condition.TRUE, last)).test(input)).isTrue();
        verify(untouched, never()).test(any());
    }

    @Test
    void sampleMatchesWhenTheDrawIsBelowTheRate()
    {
        final Condition sample = new SampleCondition(0.25);
        assertThat(sample.test(input(null, null, 0.1))).isTrue();
        assertThat(sample.test(input(null, null, 0.25))).isFalse();
        assertThat(new SampleCondition(1.0).test(input(null, null, Math.nextDown(1.0)))).isTrue();
    }

    @Test
    void everySampleOfAnExchangeTestsTheSameDraw()
    {
        // Two independent draws would make this true about a quarter of the time.
        final Condition sample = new SampleCondition(0.5);
        final Condition contradiction = new AndCondition(List.of(sample, new NotCondition(sample)));
        for (int i = 0; i < 1000; i++)
        {
            final ConditionInput exchange = ConditionInput.of(mock(GatewayRequest.class));
            assertThat(contradiction.test(exchange)).isFalse();
            assertThat(exchange.sampleDraw()).isEqualTo(exchange.sampleDraw());
        }
    }

    @Test
    void theSummaryNamesALegOtherThanTheClients()
    {
        final GatewayPredicate predicate = always();
        assertThat(new RequestCondition(predicate, Leg.CLIENT).summary()).isEqualTo("Always");
        assertThat(new RequestCondition(predicate, Leg.UPSTREAM).summary()).isEqualTo("Always (leg: upstream)");
    }
}
