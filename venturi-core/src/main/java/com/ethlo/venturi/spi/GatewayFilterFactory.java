package com.ethlo.venturi.spi;

import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.validation.ValidatableConfig;

public interface GatewayFilterFactory<F extends GatewayFilter, C extends ValidatableConfig>
{
    String name();

    /**
     * @return The specific record class Jackson 3 should map the YAML to.
     */
    default Class<C> configClass()
    {
        return null;
    }

    F create(C config);

    record EmptyConfig() implements ValidatableConfig
    {
    }
}