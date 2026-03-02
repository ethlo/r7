package com.ethlo.venturi.api;

public interface FinishedGatewayExchange extends BaseGatewayExchange
{
    CharSequence requestId();

    GatewayRequest clientRequest();

    GatewayResponse clientResponse();

    GatewayRequest upstreamRequest();

    GatewayResponse upstreamResponse();
}