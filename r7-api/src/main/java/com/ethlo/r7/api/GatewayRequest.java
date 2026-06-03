package com.ethlo.r7.api;

import java.net.InetAddress;

/**
 * An immutable representation of an HTTP request as seen by the gateway.
 */
public interface GatewayRequest
{
    /**
     * @return the HTTP method (e.g., GET, POST)
     */
    String method();

    /**
     * @return the full request URI
     */
    String uri();

    /**
     * @return the path component of the URI
     */
    String path();

    /**
     * @return the query strings
     */
    QueryParams queryParams();

    /**
     * @return the cookies
     */
    Cookies cookies();

    /**
     * @return the immutable headers received from the client
     */
    GatewayHeaders headers();

    /**
     * @return the network address of the immediate client
     */
    InetAddress remoteAddress();

    /**
     * @return the protocol version used by the client (e.g., "HTTP/1.1", "HTTP/2.0")
     */
    String protocol();
}