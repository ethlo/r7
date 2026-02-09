package com.ethlo.venturi.api;

public interface Attributes {

    <T> T get(String key);

    void put(String key, Object value);
}
