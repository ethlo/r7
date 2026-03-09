package com.ethlo.venturi.api;

import java.util.List;

/**
 * Defines a single routing rule within the gateway configuration.
 */
public interface GatewayRoute
{
    /**
     * Unique identifier for the route.
     *
     * @return the route ID
     */
    CharSequence id();

    /**
     * The destination URIs where matching traffic should be proxied.
     *
     * @return the list of upstream destination URIs
     */
    List<CharSequence> uri();

    /**
     * The logic used to determine if an incoming request matches this route.
     *
     * @return the route predicate
     */
    GatewayPredicate predicate();

    /**
     * The ordered list of filters to be applied to requests matching this route.
     *
     * @return the configured filters
     */
    List<GatewayFilter> filters();
}