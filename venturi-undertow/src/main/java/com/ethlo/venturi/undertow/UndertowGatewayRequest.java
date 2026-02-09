package com.ethlo.venturi.undertow;

import java.io.OutputStream;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.GatewayRequest;

import io.undertow.server.HttpServerExchange;

public final class UndertowGatewayRequest implements GatewayRequest {
    private final HttpServerExchange exchange;
    private final GatewayHeaders headers;

    public UndertowGatewayRequest(final HttpServerExchange exchange) {
        this.exchange = exchange;
        this.headers = new UndertowGatewayHeaders(exchange.getRequestHeaders());
    }

    @Override
    public CharSequence method() {
        return exchange.getRequestMethod().toString();
    }

    @Override
    public CharSequence uri() {
        return exchange.getRequestURI();
    }

    @Override
    public GatewayHeaders headers() {
        return headers;
    }

    @Override
    public void addStreamListener(final OutputStream out) {
        // Taps the request stream at the conduit level
        exchange.addRequestWrapper((factory, exchange) -> 
            new TeeingStreamSourceConduit(factory.create(), out));
    }
}