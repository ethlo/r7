package com.ethlo.r7.api;

/**
 * Processes the exchange after the upstream service has responded with headers.
 * <p>
 * <b>Note:</b> This stage executes on the server's primary I/O loop. Implementations
 * must not perform blocking operations here.
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