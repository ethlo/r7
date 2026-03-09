package com.ethlo.venturi.api;

public interface ClientResponseGatewayExchange extends BaseGatewayExchange
{
    GatewayRequest clientRequest();

    GatewayRequest upstreamRequest();

    GatewayResponse upstreamResponse();

    MutableGatewayResponse clientResponse();
}
