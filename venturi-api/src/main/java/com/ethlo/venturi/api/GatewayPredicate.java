package com.ethlo.venturi.api;

public interface GatewayPredicate
{
    boolean test(GatewayExchange exchange);
}