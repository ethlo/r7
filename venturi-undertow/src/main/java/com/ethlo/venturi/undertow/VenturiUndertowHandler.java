package com.ethlo.venturi.undertow;

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.ethlo.venturi.api.GatewayErrorHandler;
import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.core.GatewayExchangeDataWriter;
import com.ethlo.venturi.core.RequestIdGenerator;
import com.ethlo.venturi.core.ServerDirection;
import com.ethlo.venturi.core.SortableRequestIdGenerator;
import com.ethlo.venturi.core.StandardErrorHandler;
import com.ethlo.venturi.core.helpers.StartLineBuilder;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.ProxyHandler;

public final class VenturiUndertowHandler implements HttpHandler
{
    public static final ScopedValue<GatewayExchange> GATEWAY_EXCHANGE = ScopedValue.newInstance();

    private final GatewayRoute route;
    private final ProxyHandler proxyHandler;
    private final GatewayExchangeDataWriter gatewayExchangeDataWriter;
    private final GatewayErrorHandler errorHandler = new StandardErrorHandler();
    private final Executor executor = Executors.newFixedThreadPool(100);
    private final RequestIdGenerator requestIdGenerator = new SortableRequestIdGenerator();

    public VenturiUndertowHandler(final GatewayRoute route, final ProxyHandler proxyHandler, final GatewayExchangeDataWriter gatewayExchangeDataWriter)
    {
        this.route = route;
        this.proxyHandler = proxyHandler;
        this.gatewayExchangeDataWriter = gatewayExchangeDataWriter;
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange)
    {
        exchange.dispatch(executor, () ->
                {
                    final CharSequence requestId = requestIdGenerator.generate();
                    final UndertowGatewayRequest req = new UndertowGatewayRequest(exchange);
                    final UndertowGatewayResponse res = new UndertowGatewayResponse(exchange);
                    final MapGatewayAttributes attrs = new MapGatewayAttributes();
                    final GatewayExchange gatewayExchange = new GatewayExchange(requestId, req, res, attrs, route);

                    // Listener for complete
                    exchange.addExchangeCompleteListener((ex, nextListener) ->
                    {
                        try
                        {
                            gatewayExchangeDataWriter.complete(requestId);
                        } finally
                        {
                            nextListener.proceed();
                        }
                    });

                    // We wrap the user's listener with a 'Header Guard'
                    res.addBodyListener(new Consumer<ByteBuffer>()
                    {
                        private boolean headersWritten = false;

                        @Override
                        public void accept(ByteBuffer buffer)
                        {
                            if (!headersWritten)
                            {
                                // This is the FIRST byte of the body, i.e. we MUST write headers now.
                                final ByteBuffer startLine = StartLineBuilder.buildResponseLine(gatewayExchange);
                                gatewayExchangeDataWriter.begin(ServerDirection.RESPONSE, requestId, startLine, res.headers());
                                headersWritten = true;
                            }
                            // Now write the body chunk
                            gatewayExchangeDataWriter.writeBody(ServerDirection.RESPONSE, requestId, buffer);
                        }
                    });

                    ScopedValue.where(GATEWAY_EXCHANGE, gatewayExchange).run(() ->
                    {
                        try
                        {
                            // Capture data
                            if (captureRequestHeaders(exchange))
                            {
                                final ByteBuffer startLine = StartLineBuilder.buildRequestLine(gatewayExchange);
                                gatewayExchangeDataWriter.begin(ServerDirection.REQUEST, requestId, startLine, req.headers());
                            }

                            if (captureRequestBody(gatewayExchange))
                            {
                                req.addBodyListener(buffer -> gatewayExchangeDataWriter.writeBody(ServerDirection.REQUEST, requestId, buffer));
                            }

                            if (captureResponseBody(gatewayExchange))
                            {
                                res.addBodyListener(buffer -> gatewayExchangeDataWriter.writeBody(ServerDirection.RESPONSE, requestId, buffer));
                            }

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

    private boolean captureResponseBody(GatewayExchange gatewayExchange)
    {
        return true;
    }

    private boolean captureRequestBody(GatewayExchange gatewayExchange)
    {
        return true;
    }

    private boolean captureRequestHeaders(HttpServerExchange exchange)
    {
        return true;
    }

    private boolean captureResponseHeaders(HttpServerExchange exchange)
    {
        return true;
    }
}