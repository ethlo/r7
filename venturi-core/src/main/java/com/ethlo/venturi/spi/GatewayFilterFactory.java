package com.ethlo.venturi.spi;

import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.validation.ValidatableConfig;

public interface GatewayFilterFactory<C extends ValidatableConfig>
{
    String name();

    /**
     * @return The specific config class to map config against
     */
    default Class<C> configClass()
    {
        return null;
    }

    GatewayFilter create(C config);

    record EmptyConfig() implements ValidatableConfig
    {
    }
}