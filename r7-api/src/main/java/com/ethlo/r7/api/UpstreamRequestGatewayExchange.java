package com.ethlo.r7.api;

/**
 * Context for the phase immediately preceding the upstream proxy call.
 * <p>
 * During this phase, the client request is immutable, while the upstream request
 * can be modified to include routing headers, path rewrites, or security tokens.
 */
public interface UpstreamRequestGatewayExchange extends GatewayExchange
{
    /**
     * The original, immutable request from the client.
     *
     * @return the client request
     */
    GatewayRequest clientRequest();

    /**
     * The mutable request that will be sent to the backend.
     * <p>
     * Filters use this to perform path mapping, header injection, or method
     * overrides before the proxy handler executes.
     *
     * @return the mutable upstream request
     */
    MutableGatewayRequest upstreamRequest();

    /**
     * Aborts the proxy attempt and immediately returns a local response.
     * <p>
     * Use this if a final check (e.g., dynamic circuit breaking or backend validation)
     * fails just before the network call is dispatched.
     *
     * @param response the gateway-generated response to return to the client
     */
    void shortCircuit(ShortCircuitGatewayResponse response);
}