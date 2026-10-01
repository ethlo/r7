package com.ethlo.r7.upstream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.MutableGatewayResponse;

/**
 * The client side of a proxied exchange, as {@link UpstreamRelay} needs it. Implemented by the
 * server's own exchange rather than an adapter around it, so relaying adds no per-request object.
 * <p>
 * Teeing, counting and the request body limit are the server's; the relay reports each block it
 * moves through {@link #onRequestBody} and {@link #onResponseBody}.
 */
public interface ProxiedExchange
{
    /**
     * Whether the request arrived over HTTP/1.x, whose parser has already guaranteed what the
     * relay would otherwise check on every header: names that are tokens, and values without a
     * line break - an HTTP/1.1 head cannot even express one. Over HTTP/2 both can be sent, so
     * the relay checks, at a cost of about a tenth of the user-space instructions of a
     * passthrough request with browser-like headers. The default is to check.
     */
    default boolean parsedAsHttp1()
    {
        return false;
    }

    /**
     * The method to forward, as the pipeline leaves it.
     */
    String forwardMethod();

    /**
     * The request target to forward: the raw path and query, as sent, after filters.
     */
    String forwardTarget();

    /**
     * The request headers to forward: already sanitised by the pipeline, and changed by filters.
     * The relay rewrites Host, adds X-Forwarded-*, and sets framing for its own hop.
     */
    GatewayHeaders forwardHeaders();

    /**
     * The immediate peer's address, which is what the gateway vouches for in X-Forwarded-For
     * (the resolved client address may have come from a trusted proxy's chain).
     */
    String forwardedFor();

    /**
     * The scheme the client used, for X-Forwarded-Proto when no trusted proxy set one.
     */
    String forwardedProto();

    /**
     * The request body as the server de-framed it; read once, only when the request has one.
     */
    InputStream openRequestBody() throws IOException;

    /**
     * Called with each block of request body read, before it is sent upstream. Throwing
     * {@link RequestBodyTooLargeException} stops the exchange with 413.
     */
    default void onRequestBody(final byte[] buffer, final int offset, final int length) throws IOException
    {
    }

    /**
     * The response the client will get; the relay sets the upstream's status and headers here
     * before {@link #commit}.
     */
    MutableGatewayResponse clientResponse();

    /**
     * Commits the response - the pipeline's commit work, then the head - and returns the stream to
     * write the body to, which the relay closes to end the response. When {@code body} is false
     * (HEAD, 204, 304) the server ends the response itself and returns null.
     */
    OutputStream commit(boolean body) throws IOException;

    /**
     * Ends the client's response so that it cannot be mistaken for a complete one: the relay
     * failed after the head was committed, so part of a body has gone out. Closing the body
     * stream would let the server finish the message - write the last chunk of a chunked
     * response - and hand the client a truncated body as a whole one. The server must instead
     * drop the connection. The relay never closes the stream after calling this.
     */
    void abortResponse();

    /**
     * Whether this server can hand the client connection over to a {@link Tunnel}. Asked before
     * anything of a 101 is committed, so that a server that cannot answers 502 instead.
     */
    default boolean canUpgrade()
    {
        return false;
    }

    /**
     * Commits the 101 the relay has set on {@link #clientResponse()} and takes over the tunnel:
     * the server {@link Tunnel#run}s it with the client connection's raw streams once it has
     * them, or {@link Tunnel#close}s it if it never gets them. Only called when
     * {@link #canUpgrade()} is true.
     */
    default void upgrade(final Tunnel tunnel) throws IOException
    {
        throw new UnsupportedOperationException("This server does not tunnel upgraded connections");
    }

    /**
     * Called with each block of response body, before it is written to the client.
     */
    default void onResponseBody(final byte[] buffer, final int offset, final int length)
    {
    }

    /**
     * The target this exchange was sent to, for error reporting and the journal.
     */
    void attemptedTarget(URI target);
}
