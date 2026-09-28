package com.ethlo.r7.api;

/**
 * Context for intercepting and potentially short-circuiting an inbound client request.
 */
public interface ClientRequestGatewayExchange extends GatewayExchange
{
    /**
     * The original request as received from the client.
     *
     * @return the immutable client request
     */
    GatewayRequest clientRequest();

    /**
     * Terminates the request phase immediately, skipping subsequent request filters and upstream routing.
     * The exchange will transition directly to the response phase using the provided response.
     *
     * @param response the response to return to the client
     */
    void shortCircuit(ShortCircuitGatewayResponse response);

    /**
     * Caps the request body this exchange accepts, enforced by the server while the body streams,
     * so it holds for chunked and HTTP/2 bodies that declare no {@code Content-Length}. A body
     * that crosses the cap is never delivered whole: reading stops and the connection is closed.
     * <p>
     * Only ever lowers the effective limit; the server-wide {@code max_entity_size} still
     * applies. Must be called before the body is read, i.e. from a request filter.
     *
     * @param maxBytes the largest body, in bytes, to accept
     */
    void limitRequestBody(long maxBytes);
}