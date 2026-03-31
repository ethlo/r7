package com.ethlo.r7.spi;

import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.validation.ValidatableConfig;

public interface GatewayFilterFactory<C extends ValidatableConfig>
{
    String name();

    /**
     * @return The specific config class to map config against
     */
    Class<C> configClass();

    GatewayFilter create(C config, FilterCreationContext filterCreationContext);

    /**
     * Default placeholder for filters that have zero configuration options
     */
    record EmptyConfig() implements ValidatableConfig
    {
    }
}