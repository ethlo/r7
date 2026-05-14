package com.ethlo.r7.api;

/**
 * The core exchange representing a request/response lifecycle through the gateway.
 * <p>
 * Acts as the primary state container as the request transitions through the pipeline.
 */
public interface GatewayExchange
{
    /**
     * Unique identifier for this specific exchange, used for tracing and journaling.
     *
     * @return the unique request ID
     */
    CharSequence requestId();

    /**
     * Metadata storage exclusively for telemetry, journaling, and logging.
     * Attributes should not be used for controlling routing logic or passing functional state.
     *
     * @return the mutable attributes for observational data
     */
    MutableGatewayAttributes attributes();

    /**
     * The immutable route selected for this exchange based on initial predicates.
     *
     * @return the resolved route information
     */
    GatewayRouteInfo route();

    /**
     * Attaches strongly-typed internal state for passing functional context between filters.
     * Use this for authentication principals, routing directives, and internal filter state.
     *
     * @param key   the typed key representing the attachment
     * @param value the value to attach
     * @param <T>   the type of the attached value
     */
    <T> void setAttachment(final StateKey<T> key, final T value);

    /**
     * Retrieves a strongly-typed internal attachment from the exchange.
     *
     * @param key the typed key representing the attachment
     * @param <T> the type of the attached value
     * @return the attached value, or null if not present
     */
    <T> T getAttachment(final StateKey<T> key);

    boolean isShortCircuited();
}