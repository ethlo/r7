package com.ethlo.r7.condition;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;

/**
 * A request predicate on one leg. False while that leg has no request: on an exchange that was
 * never handed to an upstream, an upstream-leg predicate is false, and {@code not} inverts that
 * as any other false.
 */
public record RequestCondition(GatewayPredicate predicate, Leg leg) implements Condition
{
    @Override
    public boolean test(final ConditionInput input)
    {
        final GatewayRequest request = input.request(this.leg);
        return request != null && this.predicate.test(request);
    }

    @Override
    public String name()
    {
        return this.predicate.name();
    }

    @Override
    public String summary()
    {
        final String summary = this.predicate.summary();
        return this.leg == Leg.CLIENT ? summary : summary + " (leg: " + this.leg.configName() + ")";
    }
}
