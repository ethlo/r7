package com.ethlo.venturi.config.spg;

import java.util.HashMap;
import java.util.Map;

import com.ethlo.venturi.api.GatewayPredicate;

public class PredicateRegistry
{
    private final Map<String, GatewayPredicateFactory> factories = new HashMap<>();

    public PredicateRegistry()
    {
        // Register your high-speed implementations
        factories.put("Path", new PathPredicateFactory());


        //factories.put("Method", new MethodPredicateFactory());
        //factories.put("Header", new HeaderPredicateFactory());
    }

    public GatewayPredicate create(String name, String shortcutArgs)
    {
        GatewayPredicateFactory factory = factories.get(name);
        if (factory == null) throw new IllegalArgumentException("Unknown predicate: " + name);
        return factory.createFromShortcut(shortcutArgs);
    }
}