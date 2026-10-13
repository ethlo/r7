package com.ethlo.r7.api;

/**
 * A filter that is told when the route it started on hands the request to its fallback route.
 * <p>
 * Only filters whose request phase already ran for the exchange are told, and each is told once
 * per hand-over: the filters of the route that fell back, the filters it carried, and the global
 * filters. A filter declared on the fallback route itself is not told; it sees the exchange as one
 * that matched that route.
 */
public interface FallbackAwareGatewayFilter extends GatewayFilter
{
    /**
     * Called after the route's upstream was found to have no target available, before the
     * fallback route's own request filters run.
     */
    void onFallback(GatewayExchange exchange);
}
