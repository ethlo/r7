package com.ethlo.r7.spi;

import java.util.EnumSet;
import java.util.Set;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.condition.Leg;
import com.ethlo.r7.validation.ValidatableConfig;

public interface GatewayPredicateFactory<C extends ValidatableConfig>
{
    String name();

    Class<C> configClass();

    /**
     * Creates the hot-path evaluation logic (e.g., checking the path or method)
     */
    GatewayPredicate create(C config);

    /**
     * The legs the predicate may test. A predicate whose answer the upstream leg does not
     * change, or that would answer it wrongly, returns {@link Leg#CLIENT} only, and
     * {@code leg: upstream} on it is refused when the routes load.
     *
     * @return both legs, unless the predicate says otherwise
     */
    default Set<Leg> legs()
    {
        return EnumSet.allOf(Leg.class);
    }
}