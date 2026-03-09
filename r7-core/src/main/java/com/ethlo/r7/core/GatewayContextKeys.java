package com.ethlo.r7.core;

import com.ethlo.r7.api.StateKey;

public final class GatewayContextKeys
{
    // The key that downstream rate limiters will use to identify the client bucket
    public static final StateKey<String> RATE_LIMIT_KEY = new StateKey<>("rate_limit_key");

    private GatewayContextKeys()
    {
        // Prevent instantiation
    }
}