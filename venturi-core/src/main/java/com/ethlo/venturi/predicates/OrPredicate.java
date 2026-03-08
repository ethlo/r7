package com.ethlo.venturi.predicates;

import java.util.List;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRequest;

public record OrPredicate(List<GatewayPredicate> children) implements CompositePredicate
{
    @Override
    public boolean test(GatewayRequest request)
    {
        return children.stream().anyMatch(p -> p.test(request));
    }
}