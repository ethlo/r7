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
import com.ethlo.venturi.api.TerminationGatewayResponse;
import com.ethlo.venturi.api.UnproxiedUpstreamRequest;
import com.ethlo.venturi.api.UpstreamRequestGatewayExchange;
import com.ethlo.venturi.status.TrafficMetricsHandler;
import com.ethlo.venturi.time.ClockSource;
import io.undertow.server.HttpServerExchange;

public class UndertowGatewayExchange implements ClientRequestGatewayExchange, UpstreamRequestGatewayExchange, ClientResponseGatewayExchange, CompletedGatewayExchange
{
    private final HttpServerExchange undertowExchange;
    private final CharSequence requestId;
    private final GatewayRequest request;
    private MutableGatewayRequest upstreamRequest;
    private final MutableGatewayResponse response;
    private final MutableGatewayAttributes attributes;
    private final GatewayRoute route;
    private GatewayResponse upstreamResponse;
    private TerminationGatewayResponse terminated;
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
    public void terminate(final TerminationGatewayResponse terminationResponse)
    {
        terminated = terminationResponse;
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


    public void setUpstreamResponse(GatewayResponse clone)
    {
        this.upstreamResponse = clone;
    }

    public TerminationGatewayResponse getTerminated()
    {
        return terminated;
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

    public boolean wasProxied()
    {
        return undertowExchange.getAttachment(VenturiUndertowHandler.PROXY_START_TS_KEY) != null;
    }

    public void setUpstreamRequest(UnproxiedUpstreamRequest unproxiedUpstreamRequest)
    {
        this.upstreamRequest = unproxiedUpstreamRequest;
    }
}