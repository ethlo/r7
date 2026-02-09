package com.ethlo.venturi.undertow;

import java.util.Collections;

import com.ethlo.venturi.api.GatewayHeaders;

import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;

public final class UndertowGatewayHeaders implements GatewayHeaders {
    private final HeaderMap headerMap;

    public UndertowGatewayHeaders(final HeaderMap headerMap) {
        this.headerMap = headerMap;
    }

    @Override
    public String getFirst(final String name) {
        return headerMap.getFirst(name);
    }

    @Override
    public Iterable<String> getAll(final String name) {
        final var values = headerMap.get(name);
        return values != null ? values : Collections.emptyList();
    }

    @Override
    public Iterable<String> names() {
        return () -> headerMap.getHeaderNames().stream()
                .map(HttpString::toString)
                .iterator();
    }
}