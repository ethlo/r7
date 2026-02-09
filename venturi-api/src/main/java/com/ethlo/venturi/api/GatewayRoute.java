package com.ethlo.venturi.api;

/**
 * Defines a single routing rule.
 */
public interface Route {

    /**
     * Unique identifier for the route (useful for auditing/logging)
     */
    CharSequence id();

    /**
     * The destination where the traffic should be proxied
     */
    CharSequence uri();

    /**
     * The logic used to determine if this route matches a request
     */
    GatewayPredicate predicate();

    /**
     * The ordered list of filters to execute for this route
     */
    Iterable<GatewayFilter> filters();
}