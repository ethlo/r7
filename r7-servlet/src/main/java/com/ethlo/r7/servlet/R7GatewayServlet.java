package com.ethlo.r7.servlet;

import java.io.IOException;
import java.nio.file.Paths;

import com.ethlo.r7.server.blocking.BlockingGateway;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * EXPERIMENTAL: r7 as a servlet. Every request the container maps to it goes through the same
 * routes, filters, journal and pipeline as on Undertow, and is proxied by r7's own upstream
 * client; the container supplies only the socket and the HTTP parsing.
 * <p>
 * Mount it at {@code /*}. Routes match the request path below the context path, so a servlet
 * mapped more narrowly still sees the full path within its context.
 * <p>
 * Things the container decides, not r7:
 * <ul>
 *     <li>Threads. A request holds its container thread for the whole upstream exchange; give the
 *     container virtual threads (Tomcat: {@code useVirtualThreads="true"} on the connector) or
 *     size its pool for the upstream latency.</li>
 *     <li>What reaches r7 at all. A container that refuses a request itself (Tomcat refuses TRACE
 *     with 405 unless the connector allows it, and rejects encoded slashes) refuses it before
 *     r7's guards run, which is as safe.</li>
 *     <li>The client address. r7 resolves it from the connection's peer and its own trusted
 *     proxy list; do not also rewrite it in the container (Tomcat's RemoteIpValve), or r7 would
 *     trust a client-supplied address as the peer.</li>
 * </ul>
 * The server configuration's data-plane host and port are ignored - the container listens where
 * it was told to - and there is no management port.
 */
public class R7GatewayServlet extends HttpServlet
{
    /**
     * Init parameter naming the routes file; defaults to {@code R7_ROUTES_CONFIG}, then
     * {@code config/routes.yaml}.
     */
    public static final String ROUTES_PARAM = "r7.routes";

    /**
     * Init parameter naming the server file; defaults to {@code R7_SERVER_CONFIG}, then
     * {@code config/server.yaml}.
     */
    public static final String SERVER_PARAM = "r7.server";

    private transient BlockingGateway gateway;
    private transient boolean owned;

    /**
     * For a container that instantiates the servlet itself: the gateway is built in
     * {@link #init()} from the init parameters, and closed in {@link #destroy()}.
     */
    public R7GatewayServlet()
    {
    }

    /**
     * For registering programmatically: the caller built the gateway and closes it.
     */
    public R7GatewayServlet(final BlockingGateway gateway)
    {
        this.gateway = gateway;
    }

    @Override
    public void init() throws ServletException
    {
        if (this.gateway != null)
        {
            return;
        }
        final String routes = parameter(ROUTES_PARAM, "R7_ROUTES_CONFIG", "config/routes.yaml");
        final String server = parameter(SERVER_PARAM, "R7_SERVER_CONFIG", "config/server.yaml");
        try
        {
            this.gateway = new BlockingGateway(Paths.get(routes), Paths.get(server));
            this.owned = true;
        }
        catch (final IOException e)
        {
            throw new ServletException("Could not start r7 from " + routes + " and " + server, e);
        }
    }

    private String parameter(final String initParameter, final String environmentVariable, final String fallback)
    {
        final String value = getInitParameter(initParameter);
        if (value != null)
        {
            return value;
        }
        return System.getenv().getOrDefault(environmentVariable, fallback);
    }

    /**
     * Every method, TRACE and OPTIONS included: they are the pipeline's to answer, not
     * {@link HttpServlet}'s defaults.
     */
    @Override
    protected void service(final HttpServletRequest req, final HttpServletResponse resp)
    {
        this.gateway.handle(new ServletGatewayExchange(this.gateway.pipeline(), req, resp));
    }

    @Override
    public void destroy()
    {
        if (this.owned)
        {
            this.gateway.close();
        }
    }
}
