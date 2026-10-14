package com.ethlo.r7.spi;

import java.util.EnumSet;
import java.util.Set;

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
     * Where the filter may be declared. Declared at the root or on a group, a filter is one
     * instance for every route beneath it, which is wrong for one whose state belongs to a single
     * upstream: such a filter returns {@link DeclarationLevel#ROUTE} only, and the routes refuse to
     * load with it anywhere else.
     *
     * @return every level, unless the filter says otherwise
     */
    default Set<DeclarationLevel> levels()
    {
        return EnumSet.allOf(DeclarationLevel.class);
    }

    /**
     * Default placeholder for filters that have zero configuration options
     */
    record EmptyConfig() implements ValidatableConfig
    {
    }
}