package com.ethlo.venturi.undertow;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.core.ExecutableRoute;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.ProxyHandler;

public final class VenturiExecutableRoute implements ExecutableRoute
{
    private final GatewayRoute delegate;
    private final ProxyHandler proxyHandler; // Undertow-specific

    public VenturiExecutableRoute(GatewayRoute delegate, ProxyHandler httpHandler)
    {
        this.delegate = delegate;
        this.proxyHandler = httpHandler;
    }

    // Delegate data methods to the core route
    @Override
    public CharSequence id()
    {
        return delegate.id();
    }

    @Override
    public GatewayPredicate predicate()
    {
        return delegate.predicate();
    }

    @Override
    public Iterable<GatewayFilter> filters()
    {
        return delegate.filters();
    }

    @Override
    public CharSequence uri()
    {
        return delegate.uri();
    }

    @Override
    public void execute(GatewayExchange exchange) throws Exception
    {
        // 1. Run Filters
        for (GatewayFilter filter : filters())
        {
            filter.beforeUpstream(exchange);
            if (exchange.response().isCommitted())
            {
                return;
            }
        }

        // 2. Final Hop: Direct call to the cached Undertow Proxy
        final HttpServerExchange undertowExchange = ((UndertowGatewayRequest) exchange.request()).getExchange();
        proxyHandler.handleRequest(undertowExchange);
    }
}