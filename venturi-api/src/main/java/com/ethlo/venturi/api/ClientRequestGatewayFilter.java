package com.ethlo.venturi.api;

public interface ClientRequestGatewayFilter
{
    void onClientRequest(ClientRequestGatewayExchange exchange);
}