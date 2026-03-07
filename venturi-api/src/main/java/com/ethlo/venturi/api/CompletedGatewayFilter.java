package com.ethlo.venturi.api;

public interface CompletedGatewayFilter
{
    void onCompleted(CompletedGatewayExchange exchange);
}