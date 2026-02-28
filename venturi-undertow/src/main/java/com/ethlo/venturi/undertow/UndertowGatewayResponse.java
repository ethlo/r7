package com.ethlo.venturi.undertow;

import java.nio.ByteBuffer;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.GatewayResponse;
import io.undertow.server.HttpServerExchange;

public final class UndertowGatewayResponse implements GatewayResponse
{
    private final HttpServerExchange exchange;
    private final GatewayHeaders headers;
    private boolean isCommitted;

    public UndertowGatewayResponse(final HttpServerExchange exchange)
    {
        this.exchange = exchange;
        this.headers = new UndertowGatewayHeaders(exchange.getResponseHeaders());
    }

    @Override
    public GatewayHeaders headers()
    {
        return headers;
    }

    @Override
    public void status(final int status)
    {
        exchange.setStatusCode(status);
    }

    @Override
    public void commitResponse(final ByteBuffer body)
    {
        final GatewayExchange gatewayExchange = exchange.getAttachment(VenturiUndertowHandler.GATEWAY_EXCHANGE_KEY);
        gatewayExchange.getInternalState(VenturiUndertowHandler.PRE_ROUTING_COMMIT_LISTENER_KEY)
                .accept(gatewayExchange, body.duplicate().clear());
        exchange.getResponseSender().send(body);
        isCommitted = true;
    }

    @Override
    public boolean isCommitted()
    {
        return isCommitted;
    }

    @Override
    public int status()
    {
        return exchange.getStatusCode();
    }
}