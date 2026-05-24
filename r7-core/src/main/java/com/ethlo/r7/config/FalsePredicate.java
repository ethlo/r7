package com.ethlo.r7.config;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;

public class FalsePredicate implements GatewayPredicate
{
    public static final FalsePredicate INSTANCE = new FalsePredicate();

    @Override
    public boolean test(final GatewayRequest request)
    {
        return false;
    }

    @Override
    public String name()
    {
        return "false";
    }

    @Override
    public String summary()
    {
        return "false";
    }
}
