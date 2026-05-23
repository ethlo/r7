package com.ethlo.r7.predicates;

import java.util.List;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;

public record OrPredicate(List<GatewayPredicate> children) implements CompositePredicate
{
    @Override
    public boolean test(GatewayRequest request)
    {
        return children.stream().anyMatch(p -> p.test(request));
    }

    @Override
    public String name()
    {
        return "or";
    }

    @Override
    public String summary()
    {
        return "or";
    }
}