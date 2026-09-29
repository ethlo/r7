package com.ethlo.r7.config;

import java.util.List;

import com.ethlo.r7.api.GatewayRoute;

/**
 * Takes part in publishing a generation of routes, for state that has to be live together with
 * the routes it belongs to.
 * <p>
 * Whatever {@link #prepare} builds must be reachable from the route instances themselves (see
 * {@link DefaultGatewayRoute#attach}), so that the single swap of the route table publishes it:
 * state held beside the table, keyed by route id, is paired with the wrong generation for as
 * long as the two swaps are apart.
 */
public interface RouteGenerationListener
{
    /**
     * Called with a new generation before any request can match it, and with the generation in
     * service when the listener is added. Throwing rejects a new generation: the current routes
     * stay in service, and {@link #retire} is called for the rejected ones on every listener that
     * prepared them.
     */
    void prepare(List<GatewayRoute> routes);

    /**
     * Called once the given generation can no longer be matched by a new request, having been
     * replaced or rejected. Requests already holding one of its routes run to completion on it.
     */
    void retire(List<GatewayRoute> routes);
}
