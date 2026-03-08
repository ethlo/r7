package com.ethlo.venturi.api;

public interface ClientRequestGatewayFilter extends GatewayFilter
{
    void onClientRequest(ClientRequestGatewayExchange exchange);
}