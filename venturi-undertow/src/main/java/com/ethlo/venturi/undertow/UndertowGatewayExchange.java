package com.ethlo.venturi.undertow;

import com.ethlo.venturi.api.BeforeCommitGatewayExchange;
import com.ethlo.venturi.api.BeforeUpstreamGatewayExchange;
import com.ethlo.venturi.api.CompletedGatewayExchange;
import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.api.GatewayResponse;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.api.GatewayRouteInfo;
import com.ethlo.venturi.api.MutableGatewayAttributes;
import com.ethlo.venturi.api.MutableGatewayRequest;
import com.ethlo.venturi.api.MutableGatewayResponse;
import com.ethlo.venturi.api.TerminationGatewayResponse;
import com.ethlo.venturi.time.ClockSource;
import com.ethlo.venturi.undertow.experimental.TrafficMetricsHandler;
import io.undertow.server.HttpServerExchange;

public class UndertowGatewayExchange implements BeforeUpstreamGatewayExchange, BeforeCommitGatewayExchange, CompletedGatewayExchange
{
    private final HttpServerExchange undertowExchange;
    private final CharSequence requestId;
    private final GatewayRequest request;
    private final MutableGatewayRequest upstreamRequest;
    private final MutableGatewayResponse response;
    private final MutableGatewayAttributes attributes;
    private final GatewayRoute route;
    private GatewayResponse upstreamResponse;
    private TerminationGatewayResponse terminated;

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
        return new GatewayRouteInfo()
        {
            @Override
            public CharSequence id()
            {
                return route.id();
            }
        };
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
}