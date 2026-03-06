package com.ethlo.venturi.api;

public interface ClientResponseGatewayExchange
{
    CharSequence requestId();

    GatewayRequest clientRequest();

    GatewayRequest upstreamRequest();

    GatewayResponse upstreamResponse();

    MutableGatewayResponse clientResponse();

    MutableGatewayAttributes attributes();

    GatewayRouteInfo route();
}
