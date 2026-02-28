package com.ethlo.venturi.api;

public interface GatewayExchange
{
    CharSequence requestId();

    GatewayRequest request();

    MutableGatewayRequest upstreamRequest();

    GatewayResponse upstreamResponse();

    MutableGatewayResponse response();

    MutableGatewayAttributes attributes();

    GatewayRoute route();

    <T> void putInternalState(StateKey<T> key, T value);

    <T> T getInternalState(StateKey<T> key);
}