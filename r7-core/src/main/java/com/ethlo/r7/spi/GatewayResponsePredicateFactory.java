package com.ethlo.r7.spi;

import com.ethlo.r7.api.GatewayResponsePredicate;
import com.ethlo.r7.validation.ValidatableConfig;

/**
 * Creates a {@link GatewayResponsePredicate} from its configuration, as
 * {@link GatewayPredicateFactory} does for a request predicate. Registered the same way, with
 * {@code @AutoService(GatewayResponsePredicateFactory.class)}; request and response predicates
 * share one namespace of names.
 */
public interface GatewayResponsePredicateFactory<C extends ValidatableConfig>
{
    String name();

    Class<C> configClass();

    GatewayResponsePredicate create(C config);
}
