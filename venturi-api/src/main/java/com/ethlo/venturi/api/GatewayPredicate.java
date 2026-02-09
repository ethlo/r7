package com.ethlo.venturi.api;

public interface GatewayPredicate {
    boolean test(GatewayRequest request, GatewayAttributes attributes);
}