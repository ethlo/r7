package com.ethlo.venturi.undertow;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.api.GatewayResponse;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.api.MutableGatewayAttributes;
import com.ethlo.venturi.api.MutableGatewayRequest;
import com.ethlo.venturi.api.MutableGatewayResponse;
import com.ethlo.venturi.api.StateKey;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;

public class UndertowGatewayExchange implements GatewayExchange
{
    // Global registry to map Venturi's StateKeys to Undertow's AttachmentKeys.
    // This is populated statically as filters register keys, so lookups during traffic are fast.
    private static final ConcurrentMap<StateKey<?>, AttachmentKey<?>> KEY_REGISTRY = new ConcurrentHashMap<>();

    private final HttpServerExchange exchange;
    private final CharSequence requestId;
    private final GatewayRequest request;
    private final MutableGatewayRequest upstreamRequest;
    private final MutableGatewayResponse response;
    private final MutableGatewayAttributes attributes;
    private final GatewayRoute route;
    private GatewayResponse upstreamResponse;

    public UndertowGatewayExchange(
            HttpServerExchange exchange,
            CharSequence requestId,
            GatewayRequest request,
            MutableGatewayRequest upstreamRequest,
            MutableGatewayResponse response,
            GatewayResponse upstreamResponse,
            MutableGatewayAttributes attributes,
            final GatewayRoute route)
    {
        this.exchange = exchange;
        this.requestId = requestId;
        this.request = request;
        this.upstreamRequest = upstreamRequest;
        this.response = response;
        this.upstreamResponse = upstreamResponse;
        this.attributes = attributes;
        this.route = route;
    }

    @Override
    public CharSequence requestId()
    {
        return requestId;
    }

    @Override
    public GatewayRequest request()
    {
        return request;
    }

    @Override
    public MutableGatewayRequest upstreamRequest()
    {
        return upstreamRequest;
    }

    @Override
    public GatewayResponse upstreamResponse()
    {
        return upstreamResponse;
    }

    @Override
    public MutableGatewayResponse response()
    {
        return response;
    }

    @Override
    public MutableGatewayAttributes attributes()
    {
        return attributes;
    }

    @Override
    public GatewayRoute route()
    {
        return route;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void putInternalState(StateKey<T> key, T value)
    {
        AttachmentKey<T> undertowKey = (AttachmentKey<T>) KEY_REGISTRY.computeIfAbsent(
                key, k -> AttachmentKey.create(Object.class)
        );
        exchange.putAttachment(undertowKey, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getInternalState(StateKey<T> key)
    {
        AttachmentKey<?> undertowKey = KEY_REGISTRY.get(key);
        if (undertowKey == null)
        {
            return null;
        }
        return (T) exchange.getAttachment(undertowKey);
    }

    /**
     * Exposes the raw Undertow exchange for the deepest internal I/O handlers.
     * This should not be used by standard API filters.
     */
    public HttpServerExchange getRawExchange()
    {
        return exchange;
    }

    public void setUpstreamResponse(GatewayResponse clone)
    {
        this.upstreamResponse = clone;
    }
}