package com.ethlo.venturi.spi;

import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.validation.ValidatableConfig;

public interface GatewayFilterFactory<C extends ValidatableConfig>
{
    String name();

    /**
     * @return The specific record class Jackson 3 should map the YAML to.
     */
    Class<C> configClass();

    GatewayFilter create(C config);
}