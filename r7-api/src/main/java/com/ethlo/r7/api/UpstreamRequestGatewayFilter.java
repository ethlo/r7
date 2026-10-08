package com.ethlo.r7.api;

/**
 * Intercepts the exchange immediately before the request is dispatched to the upstream service.
 * <p>
 * This stage is typically used for final request transformations that depend on the
 * resolved upstream target, such as:
 * <ul>
 * <li>Dynamic path rewriting or URI mapping</li>
 * <li>Injecting backend-specific security headers or API keys</li>
 * <li>Final circuit-breaker checks to prevent calling an unhealthy backend</li>
 * </ul>
 * <p>
 * <b>Threading:</b> runs on the request's own thread, as every stage does, so a filter may
 * block here. Blocking adds to the request's latency before the upstream call starts.
 */
public interface UpstreamRequestGatewayFilter extends GatewayFilter
{
    /**
     * Invoked after the upstream request object has been initialized but before
     * the network call is executed.
     *
     * @param exchange the context providing access to both the immutable client
     *                 request and the mutable upstream request
     */
    void onUpstreamRequest(UpstreamRequestGatewayExchange exchange);
}