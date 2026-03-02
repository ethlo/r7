package com.ethlo.venturi.api;

public interface BaseGatewayExchange
{
    CharSequence requestId();

    MutableGatewayAttributes attributes();

    GatewayRouteInfo route();

    <T> void setAttachment(StateKey<T> key, T value);

    <T> T getAttachment(StateKey<T> key);
}