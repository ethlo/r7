package com.ethlo.venturi.undertow;

import java.util.HashMap;
import java.util.Map;

import com.ethlo.venturi.api.GatewayAttributes;

public final class MapGatewayAttributes implements GatewayAttributes {
    private final Map<CharSequence, Object> attributes = new HashMap<>();

    @Override
    public Iterable<CharSequence> attributeNames() {
        return attributes.keySet();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(final CharSequence key) {
        return (T) attributes.get(key);
    }

    @Override
    public void put(final CharSequence key, final Object value) {
        attributes.put(key, value);
    }
}