package com.ethlo.venturi.api;

public interface GatewayRequest
{
    CharSequence method();

    CharSequence uri();

    CharSequence path();

    CharSequence queryParams();

    GatewayHeaders headers();
}