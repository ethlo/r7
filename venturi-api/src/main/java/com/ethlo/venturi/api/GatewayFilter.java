package com.ethlo.venturi.api;

public interface GatewayFilter {

    /**
     * Initial Phase: Only Request & Attributes exist.
     */
    default void beforeUpstream(GatewayRequest req, GatewayResponse res, GatewayAttributes attrs) {
    }

    /**
     * Middle Phase: Upstream has responded with headers.
     * Now the Response object is "born."
     */
    default void onResponseHeaders(GatewayRequest req, GatewayResponse res, GatewayAttributes attrs) {
    }

    /**
     * Final Phase: Body is gone.
     * Perfect for Chronograph timing and closing audit files.
     */
    default void afterResponseBody(GatewayRequest req, GatewayResponse res, GatewayAttributes attrs) {
    }
}