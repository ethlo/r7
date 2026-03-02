package com.ethlo.venturi.undertow;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.ethlo.venturi.api.BeforeCommitGatewayExchange;
import com.ethlo.venturi.api.BeforeUpstreamGatewayExchange;
import com.ethlo.venturi.api.FinishedGatewayExchange;
import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.api.GatewayResponse;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.api.GatewayRouteInfo;
import com.ethlo.venturi.api.InitGatewayExchange;
import com.ethlo.venturi.api.MutableGatewayAttributes;
import com.ethlo.venturi.api.MutableGatewayRequest;
import com.ethlo.venturi.api.MutableGatewayResponse;
import com.ethlo.venturi.api.StateKey;
import com.ethlo.venturi.api.TerminationGatewayResponse;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import io.undertow.util.HttpString;

public class UndertowGatewayExchange implements InitGatewayExchange, BeforeUpstreamGatewayExchange, BeforeCommitGatewayExchange, FinishedGatewayExchange
{
    // Global registry to map Venturi's StateKeys to Undertow's AttachmentKeys.
    // This is populated statically as filters register keys, so lookups during traffic are fast.
    private static final ConcurrentMap<StateKey<?>, AttachmentKey<?>> KEY_REGISTRY = new ConcurrentHashMap<>();

    private final HttpServerExchange undertowExchange;
    private final CharSequence requestId;
    private final GatewayRequest request;
    private final MutableGatewayRequest upstreamRequest;
    private final MutableGatewayResponse response;
    private final MutableGatewayAttributes attributes;
    private final GatewayRoute route;
    private GatewayResponse upstreamResponse;

    public UndertowGatewayExchange(
            HttpServerExchange undertowExchange,
            CharSequence requestId,
            GatewayRequest request,
            MutableGatewayRequest upstreamRequest,
            MutableGatewayResponse response,
            GatewayResponse upstreamResponse,
            MutableGatewayAttributes attributes,
            final GatewayRoute route)
    {
        this.undertowExchange = undertowExchange;
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
    public GatewayRequest clientRequest()
    {
        return request;
    }

    @Override
    public MutableGatewayRequest upstreamRequest()
    {
        return upstreamRequest;
    }

    @Override
    public void terminate(final TerminationGatewayResponse terminationResponse)
    {
        undertowExchange.setStatusCode(terminationResponse.status());
        terminationResponse.headers().forEach(((name, value)
                -> undertowExchange.getRequestHeaders().put(HttpString.tryFromString(name.toString()), value.toString())));
        undertowExchange.getResponseSender().send(terminationResponse.body());
        clientResponse().clientResponseComitted();
    }

    @Override
    public GatewayResponse upstreamResponse()
    {
        return upstreamResponse;
    }

    @Override
    public MutableGatewayResponse clientResponse()
    {
        return response;
    }

    @Override
    public MutableGatewayAttributes attributes()
    {
        return attributes;
    }

    @Override
    public GatewayRouteInfo route()
    {
        return new GatewayRouteInfo()
        {
            @Override
            public CharSequence id()
            {
                return route.id();
            }
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void setAttachment(StateKey<T> key, T value)
    {
        AttachmentKey<T> undertowKey = (AttachmentKey<T>) KEY_REGISTRY.computeIfAbsent(
                key, k -> AttachmentKey.create(Object.class)
        );
        undertowExchange.putAttachment(undertowKey, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getAttachment(StateKey<T> key)
    {
        AttachmentKey<?> undertowKey = KEY_REGISTRY.get(key);
        if (undertowKey == null)
        {
            return null;
        }
        return (T) undertowExchange.getAttachment(undertowKey);
    }

    /**
     * Exposes the raw Undertow exchange for the deepest internal I/O handlers.
     * This should not be used by standard API filters.
     */
    public HttpServerExchange getRawExchange()
    {
        return undertowExchange;
    }

    public void setUpstreamResponse(GatewayResponse clone)
    {
        this.upstreamResponse = clone;
    }
}