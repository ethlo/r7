package com.ethlo.venturi.config.spg;

import java.util.Map;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.core.predicates.PathPredicate;

public class PathPredicateFactory implements GatewayPredicateFactory
{
    @Override
    public GatewayPredicate createFromShortcut(String pattern)
    {
        if (pattern.endsWith("/**"))
        {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return (exchange) -> exchange.request().path().toString().startsWith(prefix);
        }
        return (exchange) -> exchange.request().path().equals(pattern);
    }

    @Override
    public GatewayPredicate create(Map<String, String> args)
    {
        return null;
    }

    @Override
    public String getName()
    {
        return PathPredicate.class.getSimpleName();
    }
}