package com.ethlo.r7.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Enumeration;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.server.GatewayPipeline;
import com.ethlo.r7.server.blocking.BlockingServerExchange;
import com.ethlo.r7.server.blocking.WireHeaders;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * One request in a servlet container: the container's request and response under
 * {@link BlockingServerExchange}, on the container's request thread.
 */
final class ServletGatewayExchange extends BlockingServerExchange
{
    private final HttpServletRequest req;
    private final HttpServletResponse resp;

    ServletGatewayExchange(final GatewayPipeline pipeline, final HttpServletRequest req, final HttpServletResponse resp)
    {
        // getRequestURI(), not getServletPath() or getPathInfo(): those are decoded and
        // normalised, and the guard must see the dot segments and escapes as the client sent them.
        super(pipeline, req.getMethod(), pathWithinContext(req), req.getQueryString(), headersOf(req));
        this.req = req;
        this.resp = resp;
    }

    private static String pathWithinContext(final HttpServletRequest req)
    {
        final String uri = req.getRequestURI();
        final String context = req.getContextPath();
        return !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    private static WireHeaders headersOf(final HttpServletRequest req)
    {
        final WireHeaders headers = new WireHeaders();
        final Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements())
        {
            final String name = names.nextElement();
            final Enumeration<String> values = req.getHeaders(name);
            while (values.hasMoreElements())
            {
                headers.addFromWire(name, values.nextElement());
            }
        }
        return headers;
    }

    @Override
    protected InetSocketAddress peerAddress()
    {
        // The container's own peer: a literal address, so this never resolves a name.
        return new InetSocketAddress(InetAddress.ofLiteral(this.req.getRemoteAddr()), this.req.getRemotePort());
    }

    @Override
    protected String scheme()
    {
        return this.req.getScheme();
    }

    @Override
    protected InputStream requestBody() throws IOException
    {
        return this.req.getInputStream();
    }

    @Override
    protected void writeHead(final int status, final GatewayHeaders headers)
    {
        this.resp.setStatus(status);
        // Added, not set: a name that repeats (Set-Cookie above all) must keep every line.
        headers.forEach(this.resp, HttpServletResponse::addHeader);
    }

    @Override
    protected void send(final byte[] body) throws IOException
    {
        this.resp.setContentLength(body.length);
        final ServletOutputStream out = this.resp.getOutputStream();
        out.write(body);
        out.flush();
    }

    @Override
    protected void sendNoBody()
    {
        // The container ends the response when service() returns.
    }

    @Override
    protected OutputStream responseBody() throws IOException
    {
        return this.resp.getOutputStream();
    }

    @Override
    protected boolean isResponseStarted()
    {
        return this.resp.isCommitted();
    }

    @Override
    protected void closeConnectionAfterResponse()
    {
        this.resp.setHeader("Connection", "close");
    }
}
