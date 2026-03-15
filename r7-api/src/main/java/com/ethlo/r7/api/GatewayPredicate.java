package com.ethlo.r7.api;

/**
 * An interface used to evaluate if a {@link GatewayRequest} matches a specific route.
 */
public interface GatewayPredicate extends ShortInfo
{
    /**
     * Tests the given request against the predicate criteria.
     *
     * @param request the request to evaluate
     * @return true if the request matches, false otherwise
     */
    boolean test(GatewayRequest request);
}