package com.ethlo.venturi.api;

public interface BeforeUpstreamGatewayExchange extends BaseGatewayExchange
{
    GatewayRequest clientRequest();

    MutableGatewayRequest upstreamRequest();

    void terminate(TerminationGatewayResponse terminationResponse);
}