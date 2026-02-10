package com.ethlo.venturi.core.model;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.core.GatewayExchangeDataReader;
import com.ethlo.venturi.core.ServerDirection;
import com.ethlo.venturi.core.helpers.SimpleGatewayHeaders;

public class HeaderProvider
{
    private final GatewayExchangeDataReader gatewayExchangeDataReader;
    private final String requestId;
    private final ServerDirection serverDirection;
    private GatewayHeaders cache;

    public HeaderProvider(final GatewayExchangeDataReader gatewayExchangeDataReader, final String requestId, final ServerDirection serverDirection)
    {
        this.gatewayExchangeDataReader = gatewayExchangeDataReader;
        this.requestId = requestId;
        this.serverDirection = serverDirection;
    }

    public GatewayHeaders getHeaders()
    {
        if (cache == null)
        {
            cache = gatewayExchangeDataReader.getHeaders(serverDirection, requestId).orElseGet(SimpleGatewayHeaders::new);
        }
        return cache;
    }
}