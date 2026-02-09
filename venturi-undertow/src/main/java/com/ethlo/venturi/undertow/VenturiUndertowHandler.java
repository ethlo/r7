package com.ethlo.venturi.undertow;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayErrorHandler;
import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.core.RequestIdGenerator;
import com.ethlo.venturi.core.SortableRequestIdGenerator;
import com.ethlo.venturi.core.StandardErrorHandler;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.ProxyHandler;

public final class VenturiUndertowHandler implements HttpHandler
{
    public static final ScopedValue<CharSequence> REQUEST_ID = ScopedValue.newInstance();
    public static final ScopedValue<GatewayAttributes> ATTRS = ScopedValue.newInstance();
    private final GatewayRoute route;
    private final ProxyHandler proxyHandler;
    private final GatewayErrorHandler errorHandler = new StandardErrorHandler();
    private final Executor executor = Executors.newFixedThreadPool(100); //VirtualThreadPerTaskExecutor();
    private final RequestIdGenerator requestIdGenerator = new SortableRequestIdGenerator();
    private final AtomicLong requestNumber = new AtomicLong(0);

    public VenturiUndertowHandler(final GatewayRoute route, final ProxyHandler proxyHandler)
    {
        this.route = route;
        this.proxyHandler = proxyHandler;
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange)
    {
        exchange.dispatch(executor, () -> {

                    final UndertowGatewayRequest req = new UndertowGatewayRequest(exchange);
                    final UndertowGatewayResponse res = new UndertowGatewayResponse(exchange);
                    final MapGatewayAttributes attrs = new MapGatewayAttributes();
                    final CharSequence id = requestIdGenerator.generate();
                    final GatewayExchange gatewayExchange = new GatewayExchange(id, req, res, attrs, route);

                    ScopedValue.where(REQUEST_ID, id).where(ATTRS, attrs).run(() ->
                    {
                        try
                        {
                            // Monitor for "Silent" errors (503s from saturated ProxyClient)
                            exchange.addDefaultResponseListener(ex -> {
                                if (ex.getStatusCode() >= 400 && !ex.isResponseStarted())
                                {
                                    errorHandler.handleError(gatewayExchange, new RuntimeException("Proxy status " + ex.getStatusCode()));
                                    return true;
                                }
                                return false;
                            });

                            final boolean predicateMatch = route.predicate().test(gatewayExchange);
                            if (predicateMatch)
                            {
                                for (final GatewayFilter filter : route.filters())
                                {
                                    filter.beforeUpstream(gatewayExchange);
                                    if (exchange.isResponseStarted())
                                    {
                                        return;
                                    }
                                }
                            }

                            proxyHandler.handleRequest(exchange);
                        }
                        catch (Throwable t)
                        {
                            errorHandler.handleError(gatewayExchange, t);
                        }
                    });
                }
        );
    }
}