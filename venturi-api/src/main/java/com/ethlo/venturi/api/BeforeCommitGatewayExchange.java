package com.ethlo.venturi.api;

public interface BeforeCommitGatewayExchange
{
    CharSequence requestId();

    GatewayRequest clientRequest();

    GatewayRequest upstreamRequest();

    GatewayResponse upstreamResponse();

    MutableGatewayResponse clientResponse();

    MutableGatewayAttributes attributes();

    GatewayRouteInfo route();
}
