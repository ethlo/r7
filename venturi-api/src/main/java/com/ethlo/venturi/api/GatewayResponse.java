package com.ethlo.venturi.api;

public interface GatewayResponse
{
    GatewayHeaders headers();

    int status();
}