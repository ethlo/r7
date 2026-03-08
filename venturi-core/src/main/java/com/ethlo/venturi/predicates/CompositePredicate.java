package com.ethlo.venturi.predicates;

import java.util.List;

import com.ethlo.venturi.api.GatewayPredicate;

public interface CompositePredicate extends GatewayPredicate
{
    List<GatewayPredicate> children();
}