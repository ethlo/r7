package com.ethlo.r7.api;

/**
 * Final immutable snapshot of the exchange, once the response has been sent. On a keep-alive
 * connection this is per request, not when the connection closes.
 */
public interface CompletedGatewayExchange extends GatewayExchange
{
    /**
     * @return the unique request ID
     */
    String requestId();

    /**
     * @return the original client request
     */
    GatewayRequest clientRequest();

    /**
     * @return the final response sent to the client
     */
    GatewayResponse clientResponse();

    /**
     * @return the request sent upstream, if proxied
     */
    GatewayRequest upstreamRequest();

    /**
     * @return the response head the upstream sent; status {@code 0} and no headers when no
     * upstream sent one: the exchange was not proxied, or the proxy failed before a response
     * head arrived (a refused connect, a timeout, a protocol error), and the client got r7's own
     * {@code 502}, {@code 503} or {@code 504}
     */
    GatewayResponse upstreamResponse();

    /**
     * Indicates whether the exchange reached the upstream proxy phase.
     * * @return true if an upstream request was attempted;
     * false if the exchange was short-circuited.
     */
    boolean wasProxied();
}