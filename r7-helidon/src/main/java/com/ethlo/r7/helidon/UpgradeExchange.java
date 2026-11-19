package com.ethlo.r7.helidon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.server.GatewayPipeline;
import com.ethlo.r7.server.blocking.BlockingServerExchange;
import com.ethlo.r7.upstream.Tunnel;
import io.helidon.common.buffers.BufferData;
import io.helidon.http.HttpPrologue;
import io.helidon.http.Status;
import io.helidon.http.WritableHeaders;
import io.helidon.webserver.ConnectionContext;

/**
 * A WebSocket handshake on Níma, over the raw connection {@link WebSocketUpgrader} took from
 * Helidon: the request as Helidon parsed its head, and a response r7 writes itself. Anything but
 * a 101 is answered with {@code Connection: close} and the body runs to the close, so no framing
 * is needed and nothing is left for Helidon to parse after it.
 */
final class UpgradeExchange extends BlockingServerExchange
{
    private final ConnectionContext ctx;
    private int status;
    private GatewayHeaders headers;
    private boolean started;

    UpgradeExchange(final GatewayPipeline pipeline, final ConnectionContext ctx, final HttpPrologue prologue, final WritableHeaders<?> headers)
    {
        super(pipeline, HelidonGatewayExchange.protocolOf(prologue), prologue.method().text(),
                prologue.uriPath().rawPath(), prologue.query().rawValue(), HelidonGatewayExchange.headersOf(headers));
        this.ctx = ctx;
    }

    Tunnel openTunnel()
    {
        return tunnel();
    }

    @Override
    protected InetSocketAddress peerAddress()
    {
        final SocketAddress address = this.ctx.remotePeer().address();
        return address instanceof InetSocketAddress inet ? inet : null;
    }

    @Override
    protected InputStream requestBody()
    {
        // Helidon hands over only upgrade requests without a body.
        return InputStream.nullInputStream();
    }

    @Override
    protected boolean canSwitchProtocols()
    {
        return true;
    }

    @Override
    protected void switchProtocols(final GatewayHeaders headers) throws IOException
    {
        this.started = true;
        write(head(101, headers, false, -1));
    }

    @Override
    protected void writeHead(final int status, final GatewayHeaders headers)
    {
        // Held until the body is known: a whole body is sent with its length.
        this.status = status;
        this.headers = headers;
    }

    @Override
    protected void send(final byte[] body) throws IOException
    {
        this.started = true;
        final byte[] head = head(this.status, this.headers, true, body.length);
        final byte[] message = new byte[head.length + body.length];
        System.arraycopy(head, 0, message, 0, head.length);
        System.arraycopy(body, 0, message, head.length, body.length);
        write(message);
    }

    @Override
    protected void sendNoBody() throws IOException
    {
        this.started = true;
        write(head(this.status, this.headers, true, -1));
    }

    @Override
    protected OutputStream responseBody() throws IOException
    {
        sendNoBody();
        return new OutputStream()
        {
            @Override
            public void write(final int b) throws IOException
            {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(final byte[] buffer, final int offset, final int length) throws IOException
            {
                UpgradeExchange.this.write(buffer, offset, length);
            }
        };
    }

    @Override
    protected boolean isResponseStarted()
    {
        return this.started;
    }

    @Override
    protected void closeConnectionAfterResponse()
    {
        // Every response here but a 101 closes the connection.
    }

    /**
     * @param contentLength the body's length, or -1 to leave the headers' own Content-Length (a
     *                      relayed body's) as it is
     */
    private static byte[] head(final int status, final GatewayHeaders headers, final boolean close, final long contentLength)
    {
        final StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(status).append(' ').append(Status.create(status).reasonPhrase()).append("\r\n");
        headers.forEach(head, (h, name, value) ->
        {
            // Connection is this hop's, set below; a given length replaces any other.
            if ((!close || !name.equalsIgnoreCase("Connection")) && (contentLength < 0 || !name.equalsIgnoreCase("Content-Length")))
            {
                h.append(name).append(": ").append(value).append("\r\n");
            }
        });
        if (close)
        {
            head.append("Connection: close\r\n");
        }
        if (contentLength >= 0)
        {
            head.append("Content-Length: ").append(contentLength).append("\r\n");
        }
        head.append("\r\n");
        return head.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private void write(final byte[] bytes) throws IOException
    {
        write(bytes, 0, bytes.length);
    }

    private void write(final byte[] buffer, final int offset, final int length) throws IOException
    {
        try
        {
            // Written before it returns, so the buffer may be reused.
            this.ctx.dataWriter().writeNow(BufferData.create(buffer, offset, length));
        }
        catch (final RuntimeException e)
        {
            // Helidon reports a failed socket write unchecked.
            throw new IOException(e.getMessage(), e);
        }
    }
}
