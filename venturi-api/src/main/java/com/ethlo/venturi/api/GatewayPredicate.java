package com.ethlo.venturi.api;

public interface Predicate {
    boolean test(GatewayContext ctx);
}