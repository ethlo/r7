package com.ethlo.venturi.undertow;

import com.ethlo.venturi.api.ClientRequestGatewayExchange;
import com.ethlo.venturi.api.ClientResponseGatewayExchange;
import com.ethlo.venturi.api.CompletedGatewayExchange;
import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.api.GatewayResponse;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.api.GatewayRouteInfo;
import com.ethlo.venturi.api.MutableGatewayAttributes;
import com.ethlo.venturi.api.MutableGatewayRequest;
import com.ethlo.venturi.api.MutableGatewayResponse;
import com.ethlo.venturi.api.ShortCircuitGatewayResponse;
import com.ethlo.venturi.api.StateKey;
import com.ethlo.venturi.UnproxiedUpstreamRequest;
import com.ethlo.venturi.api.UpstreamRequestGatewayExchange;
import com.ethlo.venturi.status.TrafficMetricsHandler;
import com.ethlo.venturi.time.ClockSource;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;

public class UndertowGatewayExchange implements ClientRequestGatewayExchange, UpstreamRequestGatewayExchange, ClientResponseGatewayExchange, CompletedGatewayExchange
{
    private static final Object REGISTRY_LOCK = new Object();
    // Start with a reasonable size, it will grow automatically if needed
    private static volatile AttachmentKey<?>[] KEY_REGISTRY = new AttachmentKey<?>[32];

    private final HttpServerExchange undertowExchange;
    private final CharSequence requestId;
    private final GatewayRequest request;
    private final MutableGatewayResponse response;
    private final MutableGatewayAttributes attributes;
    private final GatewayRoute route;
    private MutableGatewayRequest upstreamRequest;
    private GatewayResponse upstreamResponse;
    private ShortCircuitGatewayResponse shortCircuitGatewayResponse;
    private long journalBytes;

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
    public void shortCircuit(final ShortCircuitGatewayResponse response)
    {
        shortCircuitGatewayResponse = response;
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
        return route::id;
    }

    @Override
    public <T> void setAttachment(final StateKey<T> key, final T value)
    {
        final AttachmentKey<T> undertowKey = getOrCreateUndertowKey(key);
        this.undertowExchange.putAttachment(undertowKey, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getAttachment(final StateKey<T> key)
    {
        final int id = key.id();
        final AttachmentKey<?>[] registry = KEY_REGISTRY;

        // Lock-free, zero-allocation array lookup
        if (id >= registry.length || registry[id] == null)
        {
            return null;
        }

        return (T) this.undertowExchange.getAttachment(registry[id]);
    }

    @SuppressWarnings("unchecked")
    private <T> AttachmentKey<T> getOrCreateUndertowKey(final StateKey<T> key)
    {
        final int id = key.id();
        AttachmentKey<?>[] registry = KEY_REGISTRY;

        // Fast path: Key already mapped
        if (id < registry.length)
        {
            final AttachmentKey<?> existing = registry[id];
            if (existing != null)
            {
                return (AttachmentKey<T>) existing;
            }
        }

        // Slow path: Map the key. This only happens once per unique StateKey during application lifecycle.
        synchronized (REGISTRY_LOCK)
        {
            registry = KEY_REGISTRY; // Re-read volatile inside lock

            // Expand array if the ID exceeds current bounds
            if (id >= registry.length)
            {
                final AttachmentKey<?>[] newRegistry = new AttachmentKey<?>[Math.max(registry.length * 2, id + 1)];
                System.arraycopy(registry, 0, newRegistry, 0, registry.length);
                registry = newRegistry;
                KEY_REGISTRY = registry; // Volatile write publishes the new array
            }

            AttachmentKey<?> undertowKey = registry[id];
            if (undertowKey == null)
            {
                undertowKey = AttachmentKey.create(Object.class);
                registry[id] = undertowKey;
            }
            return (AttachmentKey<T>) undertowKey;
        }
    }


    public void setUpstreamResponse(GatewayResponse clone)
    {
        this.upstreamResponse = clone;
    }

    public ShortCircuitGatewayResponse getShortCircuitGatewayResponse()
    {
        return shortCircuitGatewayResponse;
    }

    public long getRequestStartEpochNanos()
    {
        return ClockSource.now() - (System.nanoTime() - undertowExchange.getRequestStartTime());
    }

    public long getRequestStartNanos()
    {
        return undertowExchange.getRequestStartTime();
    }

    public TrafficMetricsHandler.TrafficMetrics getTrafficMetrics()
    {
        return undertowExchange.getAttachment(TrafficMetricsHandler.SIZE_METRICS_KEY);
    }

    public long getJournalBytes()
    {
        return journalBytes;
    }

    public void setJournalBytes(final long journalBytes)
    {
        this.journalBytes = journalBytes;
    }

    public long getDurationNanos()
    {
        return System.nanoTime() - getRequestStartNanos();
    }

    public boolean isWebsocketUpgraded()
    {
        return undertowExchange.getAttachment(VenturiUndertowHandler.IS_WEBSOCKET_KEY);
    }

    public void onWebSocketClose(Runnable closeListener)
    {
        undertowExchange.getConnection().addCloseListener(connection -> closeListener.run());
    }

    @Override
    public boolean wasProxied()
    {
        return undertowExchange.getAttachment(VenturiUndertowHandler.PROXY_START_TS_KEY) != null;
    }

    public void setUpstreamRequest(UnproxiedUpstreamRequest unproxiedUpstreamRequest)
    {
        this.upstreamRequest = unproxiedUpstreamRequest;
    }

    @Override
    public boolean isShortCircuited()
    {
        return getShortCircuitGatewayResponse() != null;
    }
}