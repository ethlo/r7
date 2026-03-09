package com.ethlo.r7.spi;

import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.validation.ValidatableConfig;

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