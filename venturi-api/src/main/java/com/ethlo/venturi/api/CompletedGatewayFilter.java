package com.ethlo.venturi.api;

/**
 * Invoked once the exchange lifecycle is fully completed and resources are ready for cleanup.
 */
public interface CompletedGatewayFilter extends GatewayFilter
{
    /**
     * Performs final telemetry recording or resource cleanup.
     *
     * @param exchange the terminal state of the gateway exchange
     */
    void onCompleted(CompletedGatewayExchange exchange);
}