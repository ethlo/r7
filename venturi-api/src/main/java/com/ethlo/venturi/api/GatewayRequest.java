package com.ethlo.venturi.api;

import java.net.InetAddress;

public interface GatewayRequest
{
    CharSequence method();

    CharSequence uri();

    CharSequence path();

    CharSequence queryParams();

    GatewayHeaders headers();

    InetAddress remoteAddress();
}