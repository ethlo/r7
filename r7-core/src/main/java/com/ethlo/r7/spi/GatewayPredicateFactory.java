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
     * {@code leg: upstream} on it is refused when the routes load. The client leg is every
     * predicate's default and the only one a route's {@code match} has, so the set must include
     * {@link Leg#CLIENT}: the gateway refuses to start with a predicate whose set does not.
     *
     * @return both legs, unless the predicate says otherwise
     */
    default Set<Leg> legs()
    {
        return EnumSet.allOf(Leg.class);
    }
}