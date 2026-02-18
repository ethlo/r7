package com.ethlo.venturi.core.predicates;

import java.util.List;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRequest;

public record AndPredicate(List<GatewayPredicate> children) implements CompositePredicate
{
    @Override
    public boolean test(GatewayRequest exchange)
    {
        return children.stream().allMatch(p -> p.test(exchange));
    }
}