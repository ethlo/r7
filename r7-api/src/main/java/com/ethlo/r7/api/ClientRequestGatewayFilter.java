package com.ethlo.r7.api;

/**
 * Intercepts incoming client requests before upstream routing.
 * <p>
 * Filters execute sequentially by mutating the {@link ClientRequestGatewayExchange}.
 * To reject a request (e.g., for auth failure or rate limiting), use the exchange's
 * short-circuit methods rather than throwing exceptions.
 */
public interface ClientRequestGatewayFilter extends GatewayFilter
{
    /**
     * Executes the filter logic on the incoming request.
     *
     * @param exchange the request context and mutable state
     */
    void onClientRequest(final ClientRequestGatewayExchange exchange);

    /**
     * Indicates if this filter performs blocking operations (e.g., network I/O, database queries).
     * <p>
     * If {@code true}, the engine dispatches execution to a worker thread (e.g., a Virtual Thread)
     * to prevent stalling the server's non-blocking I/O loop.
     *
     * @return {@code true} if blocking, {@code false} for inline fast-path execution.
     */
    default boolean requiresDispatch()
    {
        return false;
    }
}