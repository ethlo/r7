package com.ethlo.r7.core;

import com.ethlo.r7.api.StateKey;

public final class GatewayContextKeys
{
    // The key that downstream rate limiters will use to identify the client bucket
    public static final StateKey<String> RATE_LIMIT_KEY = new StateKey<>("rate_limit_key");

    /**
     * Set by a filter that has verified the client's Authorization header and consumed it: the
     * gateway then removes the client's Authorization from the upstream request before any
     * upstream filter runs, so a value a filter sets afterwards (InjectBasicAuth,
     * SetRequestHeader) always reaches the upstream - even one with the same bytes.
     */
    public static final StateKey<Boolean> CLIENT_AUTHORIZATION_CONSUMED = new StateKey<>("client_authorization_consumed");

    private GatewayContextKeys()
    {
        // Prevent instantiation
    }
}