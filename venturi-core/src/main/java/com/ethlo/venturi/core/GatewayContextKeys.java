package com.ethlo.venturi.core;

import com.ethlo.venturi.api.StateKey;

public final class GatewayContextKeys
{
    // The key that downstream rate limiters will use to identify the client bucket
    public static final StateKey<String> RATE_LIMIT_KEY = new StateKey<>("rate_limit_key");

    private GatewayContextKeys()
    {
        // Prevent instantiation
    }
}