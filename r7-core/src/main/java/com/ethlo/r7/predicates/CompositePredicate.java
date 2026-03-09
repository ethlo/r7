package com.ethlo.r7.predicates;

import java.util.List;

import com.ethlo.r7.api.GatewayPredicate;

public interface CompositePredicate extends GatewayPredicate
{
    List<GatewayPredicate> children();
}