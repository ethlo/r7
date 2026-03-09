package com.ethlo.venturi.api;

public interface CompletedGatewayFilter extends GatewayFilter
{
    void onCompleted(CompletedGatewayExchange exchange);
}