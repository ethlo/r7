package com.ethlo.r7.condition;

import com.ethlo.r7.api.GatewayResponse;
import com.ethlo.r7.api.GatewayResponsePredicate;

/**
 * A response predicate on one leg. False while that leg has no response: without an upstream
 * response head, r7's own {@code 502} or {@code 504} is the client's leg only.
 */
public record ResponseCondition(GatewayResponsePredicate predicate, Leg leg) implements Condition
{
    @Override
    public boolean test(final ConditionInput input)
    {
        final GatewayResponse response = input.response(this.leg);
        return response != null && this.predicate.test(response);
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
