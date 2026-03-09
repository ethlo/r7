package com.ethlo.venturi.api;

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
 * <b>Threading:</b> Unlike the initial client request phase, this stage is
 * strictly non-blocking. If a filter
 * requires blocking I/O to determine upstream parameters, that logic should
 * be handled in the {@link ClientRequestGatewayFilter} stage using
 * {@code requiresDispatch()}.
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