package com.ethlo.venturi.config.spg;

import java.util.Map;

import com.ethlo.venturi.api.GatewayPredicate;

public interface GatewayPredicateFactory
{
    /**
     * Create a predicate from a Spring-style shortcut string (e.g., "Path=/api/**")
     */
    default GatewayPredicate createFromShortcut(String shortcut)
    {
        throw new UnsupportedOperationException("Shortcut not supported for " + getName());
    }

    /**
     * Create a predicate from a fully expanded YAML map
     */
    GatewayPredicate create(Map<String, String> args);

    /**
     * The name used in YAML (e.g., "Path", "Header", "Method")
     */
    String getName();
}