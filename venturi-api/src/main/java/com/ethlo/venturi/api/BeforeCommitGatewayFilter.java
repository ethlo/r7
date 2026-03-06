package com.ethlo.venturi.api;

public interface BeforeCommitGatewayFilter extends GatewayFilter
{
    /**
     * The upstream service has responded with headers.
     */
    void onClientResponse(ClientResponseGatewayExchange exchange);
}
