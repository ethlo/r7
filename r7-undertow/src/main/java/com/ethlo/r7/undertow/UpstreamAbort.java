package com.ethlo.r7.undertow;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import org.xnio.IoUtils;
import org.xnio.StreamConnection;

import io.undertow.client.ClientConnection;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;

/**
 * Tears down the upstream connection of an exchange without letting the proxy finish the
 * request it was streaming.
 * <p>
 * When reading the client's body fails part-way - the client disconnected, or the body crossed
 * a size limit - Undertow's {@code ProxyHandler} closes the upstream connection with a "clean"
 * close, which shuts down writes first. For a chunked upstream request, shutting down writes
 * means writing the terminating {@code 0\r\n\r\n}: the upstream then receives a well-formed,
 * complete request whose body is silently truncated, and processes it. Closing the socket
 * underneath first leaves the upstream with a body that ends mid-chunk, which it must reject.
 * <p>
 * Only an HTTP/1.1 upstream connection needs this; the socket is reached by reflection because
 * Undertow's package-private {@code HttpClientConnection} does not expose it, following
 * {@link DiagnosticProxyClient}'s precedent for Undertow internals. Any other connection type
 * falls back to a plain close.
 */
public final class UpstreamAbort
{
    private static final AttachmentKey<ClientConnection> UPSTREAM_CONNECTION = AttachmentKey.create(ClientConnection.class);
    static final String HTTP_CLIENT_CONNECTION = "io.undertow.client.http.HttpClientConnection";
    private static final Class<?> HTTP_CLIENT_CONNECTION_CLASS;
    private static final VarHandle HTTP_CLIENT_SOCKET;

    static
    {
        try
        {
            HTTP_CLIENT_CONNECTION_CLASS = Class.forName(HTTP_CLIENT_CONNECTION);
            HTTP_CLIENT_SOCKET = MethodHandles.privateLookupIn(HTTP_CLIENT_CONNECTION_CLASS, MethodHandles.lookup())
                    .findVarHandle(HTTP_CLIENT_CONNECTION_CLASS, "connection", StreamConnection.class);
        }
        catch (final ReflectiveOperationException e)
        {
            throw new IllegalStateException("Undertow's HttpClientConnection no longer has the 'connection' field this relies on", e);
        }
    }

    private UpstreamAbort()
    {
    }

    /**
     * Records the connection the proxy is using for this exchange, so a later abort can reach it.
     */
    static void track(final HttpServerExchange exchange, final ClientConnection connection)
    {
        exchange.putAttachment(UPSTREAM_CONNECTION, connection);
    }

    /**
     * Closes the exchange's upstream socket outright. Safe to call when no upstream connection
     * has been obtained yet, and more than once.
     */
    static void abort(final HttpServerExchange exchange)
    {
        final ClientConnection connection = exchange.getAttachment(UPSTREAM_CONNECTION);
        if (HTTP_CLIENT_CONNECTION_CLASS.isInstance(connection))
        {
            IoUtils.safeClose((StreamConnection) HTTP_CLIENT_SOCKET.get((Object) connection));
        }
        else if (connection != null)
        {
            IoUtils.safeClose(connection);
        }
    }
}
