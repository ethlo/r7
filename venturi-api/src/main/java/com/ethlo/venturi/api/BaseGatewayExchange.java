package com.ethlo.venturi.api;

public interface BaseGatewayExchange
{
    CharSequence requestId();

    MutableGatewayAttributes attributes();

    GatewayRouteInfo route();
}