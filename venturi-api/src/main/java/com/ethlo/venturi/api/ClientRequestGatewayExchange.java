package com.ethlo.venturi.api;

public interface ClientRequestGatewayExchange extends BaseGatewayExchange
{
    GatewayRequest clientRequest();

    void terminate(TerminationGatewayResponse terminationResponse);
}