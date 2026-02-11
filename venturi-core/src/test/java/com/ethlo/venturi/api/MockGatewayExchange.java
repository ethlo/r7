package com.ethlo.venturi.api;

import com.ethlo.venturi.api.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Consumer;

public final class MockGatewayExchange {

    public static GatewayExchange create(final String id, final String method, final String path) {
        final Map<CharSequence, Object> attrMap = new HashMap<>();
        
        final GatewayAttributes attributes = new GatewayAttributes() {
            @Override public Iterable<CharSequence> attributeNames() { return attrMap.keySet(); }
            @Override public <T> T get(CharSequence key) { return (T) attrMap.get(key); }
            @Override public void put(CharSequence key, Object value) { attrMap.put(key, value); }
        };

        final GatewayRequest request = new GatewayRequest() {
            private final GatewayHeaders headers = new MockHeaders();
            @Override public CharSequence method() { return method; }
            @Override public CharSequence uri() { return path; }
            @Override public CharSequence path() { return path; }
            @Override public GatewayHeaders headers() { return headers; }
            @Override public void addBodyListener(Consumer<ByteBuffer> listener) {}
        };

        final GatewayResponse response = new GatewayResponse() {
            private final GatewayHeaders headers = new MockHeaders();
            private int status = 200;
            @Override public GatewayHeaders headers() { return headers; }
            @Override public void status(int s) { this.status = s; }
            @Override public int status() { return status; }
            @Override public void addBodyListener(Consumer<ByteBuffer> listener) {}
            @Override public void commitResponse(ByteBuffer body) {}
            @Override public boolean isCommitted() { return false; }
        };

        return new GatewayExchange(id, request, response, attributes, null);
    }

    private static final class MockHeaders implements GatewayHeaders {
        private final Map<CharSequence, List<CharSequence>> data = new LinkedHashMap<>();

        @Override public CharSequence getFirst(CharSequence name) {
            final List<CharSequence> values = data.get(name);
            return (values != null && !values.isEmpty()) ? values.get(0) : null;
        }

        @Override public Iterable<CharSequence> getAll(CharSequence name) {
            return data.getOrDefault(name, Collections.emptyList());
        }

        @Override public void set(CharSequence name, CharSequence value) {
            data.put(name, new ArrayList<>(List.of(value)));
        }

        @Override public void addHeader(CharSequence name, CharSequence value) {
            data.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }

        @Override public Iterable<CharSequence> names() { return data.keySet(); }
        @Override public void remove(CharSequence name) { data.remove(name); }
        @Override public void set(CharSequence name, Iterable<? extends CharSequence> values) {
            final List<CharSequence> list = new ArrayList<>();
            values.forEach(list::add);
            data.put(name, list);
        }
    }
}