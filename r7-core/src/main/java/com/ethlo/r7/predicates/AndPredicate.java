package com.ethlo.r7.predicates;

import java.util.List;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;

public record AndPredicate(List<GatewayPredicate> children) implements CompositePredicate
{
    @Override
    public boolean test(GatewayRequest exchange)
    {
        return children.stream().allMatch(p -> p.test(exchange));
    }
}