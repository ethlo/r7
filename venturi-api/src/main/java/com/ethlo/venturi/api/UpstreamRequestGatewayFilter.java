package com.ethlo.venturi.api;

public interface UpstreamRequestGatewayFilter extends GatewayFilter
{
    /**
     * Before we call the upstream service
     */
    void onUpstreamRequest(UpstreamRequestGatewayExchange exchange);
}
