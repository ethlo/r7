package com.ethlo.venturi.api;

public interface FinishedGatewayFilter
{
    void onCompleted(CompletedGatewayExchange exchange);
}