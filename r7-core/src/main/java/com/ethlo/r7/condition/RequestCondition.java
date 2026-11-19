package com.ethlo.r7.condition;

import com.ethlo.r7.api.GatewayPredicate;

/**
 * A request predicate, tested against the request as the client sent it.
 */
public record RequestCondition(GatewayPredicate predicate) implements Condition
{
    @Override
    public boolean test(final ConditionInput input)
    {
        return this.predicate.test(input.request());
    }

    @Override
    public String name()
    {
        return this.predicate.name();
    }

    @Override
    public String summary()
    {
        return this.predicate.summary();
    }
}
