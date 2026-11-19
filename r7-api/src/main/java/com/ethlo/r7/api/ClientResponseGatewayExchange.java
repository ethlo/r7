package com.ethlo.r7.api;

/**
 * Context for processing the response before it is committed to the client.
 * Contains both the original client request and the resolved upstream request/response.
 */
public interface ClientResponseGatewayExchange extends GatewayExchange
{
    /**
     * @return the original client request
     */
    GatewayRequest clientRequest();

    /**
     * @return the request as sent to the upstream service
     */
    GatewayRequest upstreamRequest();

    /**
     * @return the response head the upstream sent; status {@code 0} and no headers when no
     * upstream sent one: the exchange was not proxied, or the proxy failed before a response
     * head arrived and r7 is answering with its own {@code 502}, {@code 503} or {@code 504}
     */
    GatewayResponse upstreamResponse();

    /**
     * @return the mutable response intended for the client
     */
    MutableGatewayResponse clientResponse();

    /**
     * Indicates whether the exchange reached the upstream proxy phase.
     * * @return true if an upstream request was attempted;
     * false if the exchange was short-circuited.
     */
    boolean wasProxied();
}