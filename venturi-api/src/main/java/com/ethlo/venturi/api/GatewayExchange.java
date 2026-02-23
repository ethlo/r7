package com.ethlo.venturi.api;

/**
 * The single source of truth for a request/response cycle.
 * Designed for zero-allocation access to core gateway components.
 */

public record GatewayExchange(
        CharSequence requestId,
        GatewayRequest request,
        GatewayResponse response,
        GatewayAttributes attributes,
        GatewayRoute route
)
{
}