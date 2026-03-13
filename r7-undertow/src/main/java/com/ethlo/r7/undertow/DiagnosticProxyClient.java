package com.ethlo.r7.undertow;


import static com.ethlo.r7.undertow.R7UndertowHandler.GATEWAY_EXCHANGE_KEY;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.api.GatewayErrorHandler;
import com.ethlo.r7.core.proxy.NoAvailableTargetException;
import com.ethlo.r7.core.proxy.ProxyConnectionException;
import com.ethlo.r7.core.proxy.ProxyPoolExhaustedException;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyCallback;
import io.undertow.server.handlers.proxy.ProxyClient;
import io.undertow.server.handlers.proxy.ProxyConnection;
import io.undertow.util.AttachmentKey;

public class DiagnosticProxyClient implements ProxyClient
{
    private static final Logger logger = LoggerFactory.getLogger(DiagnosticProxyClient.class);

    // Key to track if we've already logged an error for this exchange
    private static final AttachmentKey<Boolean> ERROR_HANDLED = AttachmentKey.create(Boolean.class);

    private static final AttachmentKey<List<LoadBalancingProxyClient.Host>> ATTEMPTED_HOSTS_KEY;

    static
    {
        try
        {
            Field field = LoadBalancingProxyClient.class.getDeclaredField("ATTEMPTED_HOSTS");
            field.setAccessible(true);
            ATTEMPTED_HOSTS_KEY = (AttachmentKey<List<LoadBalancingProxyClient.Host>>) field.get(null);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to extract Undertow's internal attachment key", e);
        }
    }

    private final ProxyClient delegate;
    private final GatewayErrorHandler gatewayErrorHandler;

    public DiagnosticProxyClient(ProxyClient delegate, final GatewayErrorHandler gatewayErrorHandler)
    {
        this.delegate = delegate;
        this.gatewayErrorHandler = gatewayErrorHandler;
    }

    public static String[] getAttemptedUris(HttpServerExchange exchange)
    {
        final List<LoadBalancingProxyClient.Host> attempted = exchange.getAttachmentList(ATTEMPTED_HOSTS_KEY);
        if (attempted != null && !attempted.isEmpty())
        {
            final String[] retVal = new String[attempted.size()];
            for (int i = 0; i < attempted.size(); i++)
            {
                retVal[i] = attempted.get(i).getUri().toString();
            }
            return retVal;
        }
        return new String[0];
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
                        final String[] attemptedUris = getAttemptedUris(exchange);
                        final String actualUri = attemptedUris.length > 0 ? attemptedUris[attemptedUris.length - 1] : "Unknown";
                        reportError(exchange, new ProxyConnectionException("TCP Connection failed to: " + actualUri));
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
        return exchange.getRequestURI();
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

    private UndertowGatewayExchange getExchange(HttpServerExchange exchange)
    {
        return exchange.getAttachment(GATEWAY_EXCHANGE_KEY);
    }
}