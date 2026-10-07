package com.ethlo.r7.spi;

/**
 * What a predicate factory gets besides its own configuration: the gateway services, such as
 * the deployment's {@link com.ethlo.r7.util.Fingerprint} for summarising a sensitive value.
 */
public record PredicateCreationContext(
        EngineContext engine
)
{
}
