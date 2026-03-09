package com.ethlo.r7.api;

import java.net.InetAddress;

/**
 * An immutable representation of an HTTP request as seen by the gateway.
 */
public interface GatewayRequest
{
    /** @return the HTTP method (e.g., GET, POST) */
    CharSequence method();

    /** @return the full request URI */
    CharSequence uri();

    /** @return the path component of the URI */
    CharSequence path();

    /** @return the raw query string, or null if none */
    CharSequence queryParams();

    /** @return the immutable headers received from the client */
    GatewayHeaders headers();

    /** @return the network address of the immediate client */
    InetAddress remoteAddress();
}