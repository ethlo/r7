package com.ethlo.r7.predicates;

import java.util.List;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;

public record NotPredicate(GatewayPredicate predicate) implements CompositePredicate
{
    @Override
    public List<GatewayPredicate> children()
    {
        return List.of(predicate);
    }

    @Override
    public boolean test(final GatewayRequest request)
    {
        return !predicate.test(request);
    }

    @Override
    public String name()
    {
        return "not";
    }

    @Override
    public String summary()
    {
        return "not";
    }
}