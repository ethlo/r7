package com.ethlo.venturi.config.spg;

import java.util.Map;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;

public class AddRequestHeaderFilterFactory implements GatewayFilterFactory
{
    @Override
    public String getName()
    {
        return "AddRequestHeader";
    }

    @Override
    public GatewayFilter create(Map<String, String> args)
    {
        // SCG shorthand often maps to "name" and "value"
        final String name = args.get("_genkey_0"); // First arg in shortcut
        final String value = args.get("_genkey_1"); // Second arg in shortcut

        return new GatewayFilter()
        {
            @Override
            public void beforeUpstream(GatewayExchange exchange)
            {
                exchange.request().headers().addHeader(name, value);
            }
        };
    }
}