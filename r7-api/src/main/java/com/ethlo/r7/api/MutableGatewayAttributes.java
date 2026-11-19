package com.ethlo.r7.api;

/**
 * A mutable container for gateway-specific metadata and telemetry data.
 * <p>
 * This interface combines the read-only {@link GatewayAttributes} with the
 * mutation capabilities of {@link MutableMultiAttributes}.
 * It is primarily used by the engine to store observational data for journaling
 * and by filters to pass internal context that should not be transmitted as
 * HTTP headers.
 */
public interface MutableGatewayAttributes extends MutableMultiAttributes, GatewayAttributes
{
}