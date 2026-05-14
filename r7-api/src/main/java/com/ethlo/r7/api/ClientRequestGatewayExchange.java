package com.ethlo.r7.api;

/**
 * Context for intercepting and potentially short-circuiting an inbound client request.
 */
public interface ClientRequestGatewayExchange extends GatewayExchange
{
    /**
     * The original request as received from the client.
     *
     * @return the immutable client request
     */
    GatewayRequest clientRequest();

    /**
     * Terminates the request phase immediately, skipping subsequent request filters and upstream routing.
     * The exchange will transition directly to the response phase using the provided response.
     *
     * @param response the response to return to the client
     */
    void shortCircuit(ShortCircuitGatewayResponse response);
}