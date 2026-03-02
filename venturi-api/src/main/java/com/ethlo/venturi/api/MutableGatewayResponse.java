package com.ethlo.venturi.api;

public interface MutableGatewayResponse extends GatewayResponse
{
    MutableGatewayHeaders headers();

    void status(int status);
}