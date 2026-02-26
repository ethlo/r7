package com.ethlo.venturi.spi;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.validation.ValidatableConfig;

public interface GatewayPredicateFactory<C extends ValidatableConfig>
{
    String name();

    Class<C> configClass();

    /**
     * Creates the hot-path evaluation logic (e.g., checking the path or method)
     */
    GatewayPredicate create(C config);
}