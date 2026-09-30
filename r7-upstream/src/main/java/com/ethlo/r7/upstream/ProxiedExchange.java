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
 * Teeing, counting and the request body limit are the server's: it already sees every byte at its
 * own layer (Undertow in its conduits), and the relay reports each block it moves through
 * {@link #onRequestBody} and {@link #onResponseBody} for a server that does not.
 */
public interface ProxiedExchange
{
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
