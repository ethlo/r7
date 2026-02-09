package com.ethlo.venturi.undertow;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.GatewayResponse;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.OutputStream;
import java.nio.ByteBuffer;

public final class UndertowGatewayResponse implements GatewayResponse {
    private final HttpServerExchange exchange;
    private final GatewayHeaders headers;
    private boolean isCommitted;

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
        exchange.addResponseWrapper((factory, ex) -> new TeeingStreamSinkConduit(factory.create(), out));
    }

    @Override
    public void localResponse(final byte[] body, final CharSequence contentType) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType.toString());
        exchange.getResponseSender().send(ByteBuffer.wrap(body));
        isCommitted = true;
    }

    @Override
    public boolean isCommitted() {
        return isCommitted;
    }
}