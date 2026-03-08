package com.ethlo.venturi.api;

import java.util.List;

/**
 * Defines a single routing rule.
 */
public interface GatewayRoute
{
    /**
     * Unique identifier for the route
     */
    CharSequence id();

    /**
     * The destination where the traffic should be proxied
     */
    List<CharSequence> uri();

    /**
     * The logic used to determine if this route matches a request
     */
    GatewayPredicate predicate();


    List<GatewayFilter> filters();
}