package com.ethlo.venturi.api;

/**
 * Final immutable snapshot of the exchange after the client connection is closed.
 */
public interface CompletedGatewayExchange extends GatewayExchange
{
    /**
     * @return the unique request ID
     */
    CharSequence requestId();

    /**
     * @return the original client request
     */
    GatewayRequest clientRequest();

    /**
     * @return the final response sent to the client
     */
    GatewayResponse clientResponse();

    /**
     * @return the request sent upstream, if proxied
     */
    GatewayRequest upstreamRequest();

    /**
     * @return the response received from upstream, if proxied
     */
    GatewayResponse upstreamResponse();

    /**
     * Indicates whether the exchange reached the upstream proxy phase.
     * * @return true if an upstream request was attempted;
     * false if the exchange was short-circuited.
     */
    boolean wasProxied();
}