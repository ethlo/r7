package com.ethlo.venturi.undertow;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.GatewayErrorHandler;
import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.core.DataBufferRepository;
import com.ethlo.venturi.core.RequestIdGenerator;
import com.ethlo.venturi.core.ServerDirection;
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
    private final DataBufferRepository repository; // Added repository
    private final GatewayErrorHandler errorHandler = new StandardErrorHandler();
    private final Executor executor = Executors.newFixedThreadPool(100);
    private final RequestIdGenerator requestIdGenerator = new SortableRequestIdGenerator();

    public VenturiUndertowHandler(final GatewayRoute route, final ProxyHandler proxyHandler, final DataBufferRepository repository)
    {
        this.route = route;
        this.proxyHandler = proxyHandler;
        this.repository = repository;
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange)
    {
        exchange.dispatch(executor, () ->
                {
                    final CharSequence requestId = requestIdGenerator.generate();

                    final UndertowGatewayRequest req = new UndertowGatewayRequest(exchange);
                    final UndertowGatewayResponse res = new UndertowGatewayResponse(exchange);

                    req.addStreamListener(new RepositoryOutputStream(repository, requestId, ServerDirection.REQUEST));
                    res.addStreamListener(new RepositoryOutputStream(repository, requestId, ServerDirection.RESPONSE));

                    final MapGatewayAttributes attrs = new MapGatewayAttributes();

                    final GatewayExchange gatewayExchange = new GatewayExchange(requestId, req, res, attrs, route);

                    exchange.addExchangeCompleteListener((ex, nextListener) ->
                    {
                        try
                        {
                            repository.closePayloadChannels(requestId);
                        } finally
                        {
                            nextListener.proceed();
                        }
                    });

                    ScopedValue.where(REQUEST_ID, requestId).where(ATTRS, attrs).run(() ->
                    {
                        try
                        {
                            repository.putHeaders(ServerDirection.REQUEST, requestId, req.headers());

                            // Monitor for "Silent" errors
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