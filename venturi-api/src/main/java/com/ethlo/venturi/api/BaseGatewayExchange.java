package com.ethlo.venturi.api;

/**
 * The core exchange representing a request/response lifecycle through the gateway.
 */
public interface BaseGatewayExchange {

    CharSequence requestId();

    /**
     * Retrieves the mutable attributes associated with this exchange.
     * Attributes are exclusively used for storing telemetry, journaling,
     * and logging metadata. They should not be used for controlling
     * gateway routing logic or passing functional state between filters.
     *
     * @return the mutable attributes for observational data
     */
    MutableGatewayAttributes attributes();

    GatewayRouteInfo route();

    /**
     * Attaches strongly-typed internal state to the exchange.
     * Attachments are strictly for passing functional state, routing
     * directives, and internal context between gateway filters.
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
}