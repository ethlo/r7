package com.ethlo.r7.api;

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
    String id();

    /**
     * The destination URIs where matching traffic should be proxied.
     *
     * @return the list of upstream destination URIs
     */
    List<String> uri();

    /**
     * The ordered list of filters to be applied to requests matching this route.
     *
     * @return the configured filters
     */
    List<GatewayFilter> filters();
}