package com.ethlo.venturi.api;

public interface ClientRequestGatewayFilter
{

    void onClientRequest(ClientRequestGatewayExchange exchange);

    /**
     * Indicates whether this filter performs blocking operations, such as
     * synchronous database queries or external HTTP calls.
     * * By default, filters execute inline on the highly optimized Undertow IO thread.
     * If this method returns true, the gateway engine will dispatch the execution
     * of this filter (and subsequent request filters) to a Virtual Thread. This
     * prevents the blocking operation from stalling the underlying non-blocking
     * network event loop.
     *
     * @return true if the filter requires dispatching off the IO thread, false otherwise.
     */
    default boolean requiresDispatch()
    {
        return false;
    }
}