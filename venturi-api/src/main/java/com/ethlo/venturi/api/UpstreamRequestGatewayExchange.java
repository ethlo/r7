package com.ethlo.venturi.api;

public interface UpstreamRequestGatewayExchange extends BaseGatewayExchange
{
    GatewayRequest clientRequest();

    MutableGatewayRequest upstreamRequest();

    void terminate(TerminationGatewayResponse terminationResponse);
}