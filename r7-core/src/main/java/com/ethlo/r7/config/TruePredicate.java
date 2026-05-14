package com.ethlo.r7.config;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;

public class TruePredicate implements GatewayPredicate
{
    public static final TruePredicate INSTANCE = new TruePredicate();

    @Override
    public boolean test(final GatewayRequest request)
    {
        return true;
    }

    @Override
    public String name()
    {
        return "true";
    }

    @Override
    public String summary()
    {
        return "true";
    }
}
