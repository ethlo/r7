package com.ethlo.r7.api;

/**
 * Processes the exchange after the upstream service has responded with headers.
 * <p>
 * <b>Threading:</b> runs on the request's own thread, as every stage does, so a filter may
 * block here. Blocking holds back the response to the client while it waits.
 */
public interface ClientResponseGatewayFilter extends GatewayFilter
{
    /**
     * Invoked when the upstream response headers are available.
     *
     * @param exchange the context for the outbound response phase
     */
    void onClientResponse(ClientResponseGatewayExchange exchange);
}