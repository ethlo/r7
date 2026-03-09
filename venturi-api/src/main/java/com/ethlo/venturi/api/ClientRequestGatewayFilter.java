package com.ethlo.venturi.api;

public interface ClientRequestGatewayFilter extends GatewayFilter
{
    /**
     * Indicates if this filter performs blocking operations (e.g., synchronous network I/O).
     * If true, the pipeline engine will dispatch this filter's execution to a
     * Virtual Thread to prevent stalling the underlying non-blocking IO loop.
     *
     * @return true if the filter requires dispatching off the IO thread.
     */
    default boolean requiresDispatch()
    {
        return false;
    }

    void onClientRequest(ClientRequestGatewayExchange exchange);
}