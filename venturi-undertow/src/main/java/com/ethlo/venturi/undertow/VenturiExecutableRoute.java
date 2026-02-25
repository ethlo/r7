package com.ethlo.venturi.undertow;

import static com.ethlo.venturi.undertow.VenturiUndertowHandler.JOURNAL_KEY;
import static com.ethlo.venturi.undertow.VenturiUndertowHandler.REQUEST_BYTES_READ_KEY;
import static com.ethlo.venturi.undertow.VenturiUndertowHandler.REQUEST_START_NANOS_KEY;
import static com.ethlo.venturi.undertow.VenturiUndertowHandler.RESPONSE_BYTES_SENT_KEY;

import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.config.RouteDefinition;
import com.ethlo.venturi.core.ExecutableRoute;
import com.ethlo.venturi.vlf.VlfJournal;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;

public final class VenturiExecutableRoute implements ExecutableRoute
{
    private final RouteDefinition definition;
    private final GatewayRoute delegate;
    private final HttpHandler proxyHandler; // Undertow-specific

    public VenturiExecutableRoute(RouteDefinition definition, GatewayRoute delegate, HttpHandler httpHandler)
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
    public List<CharSequence> uri()
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

                handleRequestEnded(exchange);
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

    private void handleRequestEnded(final GatewayExchange gatewayExchange)
    {
        final VlfJournal journal = Objects.requireNonNull(gatewayExchange.getInternalState(JOURNAL_KEY), "Journal is required");
        final long startNanos = gatewayExchange.getInternalState(REQUEST_START_NANOS_KEY);
        final LongSupplier requestBytesRead = Objects.requireNonNull(gatewayExchange.getInternalState(REQUEST_BYTES_READ_KEY));
        final LongSupplier responseBytesSent = Objects.requireNonNull(gatewayExchange.getInternalState(RESPONSE_BYTES_SENT_KEY));
        synchronized (journal)
        {
            journal.end(gatewayExchange.requestId(), gatewayExchange.attributes(), gatewayExchange.response().status(), responseBytesSent.getAsLong(), requestBytesRead.getAsLong(), System.nanoTime() - startNanos);
        }
    }
}