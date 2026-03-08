package com.ethlo.venturi.predicates;

import java.util.List;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRequest;

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
}