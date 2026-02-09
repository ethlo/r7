package com.ethlo.venturi.api;

public interface GatewayFilter
{

    /**
     * Before we call upstream service
     */
    default void beforeUpstream(GatewayExchange exchange)
    {
    }

    /**
     * Upstream has responded with headers.
     */
    default void onResponseHeaders(GatewayExchange exchange)
    {
    }

    /**
     * After the gateway has pushed both the headers and body back to the client
     */
    default void finished(GatewayExchange exchange)
    {
    }
}