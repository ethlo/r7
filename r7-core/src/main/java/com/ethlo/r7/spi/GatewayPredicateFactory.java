package com.ethlo.r7.spi;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.validation.ValidatableConfig;

public interface GatewayPredicateFactory<C extends ValidatableConfig>
{
    String name();

    Class<C> configClass();

    /**
     * Creates the hot-path evaluation logic (e.g., checking the path or method)
     */
    GatewayPredicate create(C config);
}