package com.ethlo.venturi.api;

/**
 * Standardized hook for handling gateway-level failures.
 */
public interface GatewayErrorHandler
{
    /**
     * Called when an unhandled exception occurs or the engine returns a non-2xx
     * status without a body (e.g., a 503 from a saturated ProxyClient).
     */
    void handleError(UpstreamRequestGatewayExchange exchange, Throwable error);
}