package com.ethlo.venturi.undertow;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.config.RouteDefinition;
import com.ethlo.venturi.core.ExecutableRoute;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.ProxyHandler;

public final class VenturiExecutableRoute implements ExecutableRoute
{
    private final RouteDefinition definition;
    private final GatewayRoute delegate;
    private final ProxyHandler proxyHandler; // Undertow-specific

    public VenturiExecutableRoute(RouteDefinition definition, GatewayRoute delegate, ProxyHandler httpHandler)
    {
        this.definition = definition;
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
    public RouteDefinition routeDefinition()
    {
        return definition;
    }

    @Override
    public void execute(GatewayExchange exchange) throws Exception
    {
        final HttpServerExchange undertowExchange = ((UndertowGatewayRequest) exchange.request()).getExchange();
        final Iterable<GatewayFilter> filters = filters();

        // 2. Hook 'finished' callback
        undertowExchange.addExchangeCompleteListener((ex, next) -> {
            try
            {
                for (GatewayFilter filter : filters())
                {
                    filter.finished(exchange);
                }
            } finally
            {
                next.proceed();
            }
        });

        // 1. Initial Logic (Sync)
        for (GatewayFilter filter : filters)
        {
            filter.init(exchange);
        }

        // 2. Wire the "Finished" lifecycle hook immediately
        undertowExchange.addExchangeCompleteListener((ex, next) -> {
            try
            {
                for (GatewayFilter filter : filters)
                {
                    filter.finished(exchange);
                }
            } finally
            {
                next.proceed(); // Essential for Undertow to clean up
            }
        });

        // 3. Wire the "Response Headers" hook
        // This triggers just before headers are flushed to the client
        undertowExchange.addResponseCommitListener(ex -> {
            for (GatewayFilter filter : filters)
            {
                filter.onResponseHeaders(exchange);
            }
        });

        // 4. Run "Before Upstream"
        for (GatewayFilter filter : filters)
        {
            filter.beforeUpstream(exchange);
            if (exchange.response().isCommitted())
            {
                return; // Filter chose to end the request locally
            }
        }

        // 5. Final Hop
        proxyHandler.handleRequest(undertowExchange);
    }
}