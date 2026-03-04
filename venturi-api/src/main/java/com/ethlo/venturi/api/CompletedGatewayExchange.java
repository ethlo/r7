package com.ethlo.venturi.api;

public interface CompletedGatewayExchange extends BaseGatewayExchange
{
    CharSequence requestId();

    GatewayRequest clientRequest();

    GatewayResponse clientResponse();

    GatewayRequest upstreamRequest();

    GatewayResponse upstreamResponse();
}