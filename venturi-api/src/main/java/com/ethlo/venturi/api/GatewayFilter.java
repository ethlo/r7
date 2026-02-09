package com.ethlo.venturi.api;

public interface Filter {

    default void beforeUpstream(GatewayContext ctx) throws Exception {
    }

    default void onResponseHeaders(GatewayContext ctx) throws Exception {
    }

    default void afterResponseBody(GatewayContext ctx) throws Exception {
    }
}
