package com.ethlo.r7.helidon;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.server.GatewayPipeline;
import com.ethlo.r7.server.blocking.BlockingServerExchange;
import com.ethlo.r7.server.blocking.WireHeaders;
import io.helidon.http.Header;
import io.helidon.http.HeaderNames;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

/**
 * One request on Helidon's Níma web server: Helidon's request and response objects under
 * {@link BlockingServerExchange}, on the request's own virtual thread.
 */
final class HelidonGatewayExchange extends BlockingServerExchange
{
    private final ServerRequest req;
    private final ServerResponse res;

    HelidonGatewayExchange(final GatewayPipeline pipeline, final ServerRequest req, final ServerResponse res)
    {
        // Not UriPath.path(): Helidon resolves dot segments there, and the guard must see them.
        super(pipeline, protocolOf(req), req.prologue().method().text(),
                req.prologue().uriPath().rawPath(), req.prologue().query().rawValue(), headersOf(req));
        this.req = req;
        this.res = res;
    }

    /**
     * "HTTP/1.1" or "HTTP/2.0", without building a string for the common case.
     */
    private static String protocolOf(final ServerRequest req)
    {
        final String version = req.prologue().protocolVersion();
        return "1.1".equals(version) ? "HTTP/1.1" : req.prologue().protocol() + '/' + version;
    }

    private static WireHeaders headersOf(final ServerRequest req)
    {
        final WireHeaders headers = new WireHeaders();
        for (final Header header : req.headers())
        {
            final String name = header.name();
            for (final String value : header.allValues())
            {
                headers.addFromWire(name, value);
            }
        }
        return headers;
    }

    @Override
    protected InetSocketAddress peerAddress()
    {
        final SocketAddress address = this.req.remotePeer().address();
        return address instanceof InetSocketAddress inet ? inet : null;
    }

    @Override
    protected InputStream requestBody()
    {
        return this.req.content().inputStream();
    }

    @Override
    protected void writeHead(final int status, final GatewayHeaders headers)
    {
        this.res.status(status);
        // Added, not set: a name that repeats (Set-Cookie above all) must keep every line.
        headers.forEach(this.res.headers(), (h, name, value) -> h.add(HeaderNames.create(name), value));
    }

    @Override
    protected void send(final byte[] body)
    {
        this.res.send(body);
    }

    @Override
    protected void sendNoBody()
    {
        this.res.send();
    }

    @Override
    protected OutputStream responseBody()
    {
        return this.res.outputStream();
    }

    @Override
    protected boolean isResponseStarted()
    {
        return this.res.isSent();
    }

    @Override
    protected void closeConnectionAfterResponse()
    {
        this.res.header("Connection", "close");
    }
}
