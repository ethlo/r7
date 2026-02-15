package com.ethlo.venturi.undertow;


import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.api.GatewayErrorHandler;
import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.core.proxy.NoAvailableTargetException;
import com.ethlo.venturi.core.proxy.ProxyConnectionException;
import com.ethlo.venturi.core.proxy.ProxyPoolExhaustedException;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.ProxyCallback;
import io.undertow.server.handlers.proxy.ProxyClient;
import io.undertow.server.handlers.proxy.ProxyConnection;
import io.undertow.util.AttachmentKey;

public class DiagnosticProxyClient implements ProxyClient
{
    private static final Logger logger = LoggerFactory.getLogger(DiagnosticProxyClient.class);

    // Key to track if we've already logged an error for this exchange
    private static final AttachmentKey<Boolean> ERROR_HANDLED = AttachmentKey.create(Boolean.class);

    private final ProxyClient delegate;
    private final GatewayErrorHandler gatewayErrorHandler;

    public DiagnosticProxyClient(ProxyClient delegate, final GatewayErrorHandler gatewayErrorHandler)
    {
        this.delegate = delegate;
        this.gatewayErrorHandler = gatewayErrorHandler;
    }

    @Override
    public void getConnection(ProxyTarget target, HttpServerExchange exchange,
                              ProxyCallback<ProxyConnection> callback,
                              long timeout, TimeUnit timeUnit)
    {

        delegate.getConnection(target, exchange, new ProxyCallback<>()
                {
                    @Override
                    public void completed(HttpServerExchange exchange, ProxyConnection result)
                    {
                        callback.completed(exchange, result);
                    }

                    @Override
                    public void failed(HttpServerExchange exchange)
                    {
                        // Handshake/Refused -> 502 Bad Gateway candidate
                        reportError(exchange, new ProxyConnectionException("TCP Connection failed to: " + getTarget(exchange)));
                        callback.failed(exchange);
                    }

                    @Override
                    public void couldNotResolveBackend(HttpServerExchange exchange)
                    {
                        // DNS/Target dead -> 502 Bad Gateway candidate
                        reportError(exchange, new NoAvailableTargetException("Backend unreachable: " + getTarget(exchange)));
                        callback.couldNotResolveBackend(exchange);
                    }

                    @Override
                    public void queuedRequestFailed(HttpServerExchange exchange)
                    {
                        // Pool full -> 503 Service Unavailable candidate
                        reportError(exchange, new ProxyPoolExhaustedException("Connection pool full for: " + getTarget(exchange)));
                        callback.queuedRequestFailed(exchange);
                    }
                }, timeout, timeUnit
        );
    }

    private CharSequence getTarget(HttpServerExchange exchange)
    {
        return getExchange(exchange).route().toString();
    }

    @Override
    public List<ProxyTarget> getAllTargets()
    {
        return delegate.getAllTargets();
    }

    @Override
    public ProxyTarget findTarget(final HttpServerExchange exchange)
    {
        final ProxyTarget target = delegate.findTarget(exchange);
        if (target == null)
        {
            logger.debug("LB returned null for thread {}", Thread.currentThread().getName());
        }
        return target;
    }

    /**
     * Ensures we only call the error handler once per request
     */
    private void reportError(HttpServerExchange exchange, Throwable t)
    {
        if (exchange.getAttachment(ERROR_HANDLED) == null)
        {
            gatewayErrorHandler.handleError(getExchange(exchange), t);
            exchange.putAttachment(ERROR_HANDLED, true);
        }
    }

    private GatewayExchange getExchange(HttpServerExchange exchange)
    {
        return null; //exchange.getAttachment(GATEWAY_EXCHANGE_KEY);
    }
}