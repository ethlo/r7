package com.ethlo.venturi.api;

public interface FinishedGatewayFilter
{
    void completed(CompletedGatewayExchange exchange);
}