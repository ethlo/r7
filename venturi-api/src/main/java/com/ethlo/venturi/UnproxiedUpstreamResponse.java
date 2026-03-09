package com.ethlo.venturi;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.GatewayResponse;
import com.ethlo.venturi.api.MutableGatewayHeaders;

/**
 * A sentinel implementation of {@link GatewayResponse} representing a response state
 * when the gateway did not communicate with an upstream service.
 * <p>
 * This is the terminal state for the upstream response in short-circuit scenarios.
 */
public final class UnproxiedUpstreamResponse implements GatewayResponse
{
    /**
     * The singleton instance representing a null or skipped upstream response.
     */
    public static final UnproxiedUpstreamResponse INSTANCE = new UnproxiedUpstreamResponse();

    private UnproxiedUpstreamResponse()
    {
    }

    /**
     * @return 0, indicating the response was generated locally by the gateway
     */
    @Override
    public int status()
    {
        return 0;
    }

    /**
     * @return an empty headers container
     */
    @Override
    public GatewayHeaders headers()
    {
        return MutableGatewayHeaders.EMPTY;
    }
}