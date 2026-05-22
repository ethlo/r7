package com.ethlo.r7.api;

public interface MutableGatewayHeaders extends GatewayHeaders, MutableMultiAttributes
{
    MutableGatewayHeaders set(final String name, final String value);
}