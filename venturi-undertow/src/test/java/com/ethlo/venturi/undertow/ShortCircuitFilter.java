package com.ethlo.venturi.undertow;

public final class ShortCircuitFilter implements GatewayFilter {
    @Override
    public void beforeUpstream(GatewayRequest req, GatewayAttributes attrs) {
        // Terminates the request here. The engine will NOT call proxyHandler.
        // This is the ultimate test of the "Gateway Overhead".
        ((UndertowGatewayRequest)req).getExchange().getResponseSender().send("OK");
    }
}