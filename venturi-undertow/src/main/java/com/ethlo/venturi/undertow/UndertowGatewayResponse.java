package com.ethlo.venturi.undertow;

import java.io.OutputStream;
import java.nio.ByteBuffer;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.GatewayResponse;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

public final class UndertowGatewayResponse implements GatewayResponse {
    private final HttpServerExchange exchange;
    private final GatewayHeaders headers;

    public UndertowGatewayResponse(final HttpServerExchange exchange) {
        this.exchange = exchange;
        this.headers = new UndertowGatewayHeaders(exchange.getResponseHeaders());
    }

    @Override
    public GatewayHeaders headers() {
        return headers;
    }

    @Override
    public void setStatus(final int status) {
        exchange.setStatusCode(status);
    }

    @Override
    public void addStreamListener(final OutputStream out) {
        // Taps the response stream at the conduit level
        exchange.addResponseWrapper((factory, exchange) -> 
            new TeeingStreamSinkConduit(factory.create(), out));
    }

    @Override
    public void localResponse(final byte[] body, final CharSequence contentType) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType.toString());
        exchange.getResponseSender().send(ByteBuffer.wrap(body));
    }
}