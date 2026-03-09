package com.ethlo.venturi.api;

public interface ClientRequestGatewayFilter {

    void onClientRequest(final ClientRequestGatewayExchange exchange);

    /**
     * Indicates whether this filter performs blocking operations, such as
     * synchronous database queries, heavy cryptographic work, or external HTTP calls.
     * <p>
     * By default, filters are expected to be non-blocking and execute inline on the
     * server's primary network I/O thread to maximize throughput. If this method
     * returns true, the underlying gateway engine is instructed to dispatch the
     * execution of this filter to a dedicated worker thread (e.g., a Virtual Thread).
     * This ensures that blocking operations do not stall the server's network event loop.
     *
     * @return true if the filter requires dispatching off the primary I/O thread, false otherwise.
     */
    default boolean requiresDispatch()
    {
        return false;
    }
}