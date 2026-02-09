package com.ethlo.venturi.api;

public interface GatewayAttributes
{
    Iterable<CharSequence> attributeNames();

    <T> T get(CharSequence key);

    void put(CharSequence key, Object value);
}
