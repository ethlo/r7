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
     * The servers r7 ships give each request a thread of its own, on which blocking is fine, so
     * there this has no effect and the filter runs inline either way. It is honoured only by a
     * server that runs requests on a shared I/O thread, which then moves the request to a thread
     * that may block before calling the filter.
     *
     * @return {@code true} if blocking, {@code false} otherwise.
     */
    default boolean requiresDispatch()
    {
        return false;
    }
}