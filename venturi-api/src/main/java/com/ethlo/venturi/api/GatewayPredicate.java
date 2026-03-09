package com.ethlo.venturi.api;

/**
 * A functional interface used to evaluate if a {@link GatewayRequest} matches a specific route.
 */
@FunctionalInterface
public interface GatewayPredicate
{
    /**
     * Tests the given request against the predicate criteria.
     *
     * @param request the request to evaluate
     * @return true if the request matches, false otherwise
     */
    boolean test(GatewayRequest request);
}