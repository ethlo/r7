package com.ethlo.venturi.core.filters;

import java.util.Map;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;

public class AddRequestHeaderFilter implements GatewayFilter
{
    private final CharSequence name;
    private final CharSequence value;

    public AddRequestHeaderFilter(Map<String, String> args)
    {
        this.name = args.get("name");
        this.value = args.get("value");
    }

    @Override
    public void beforeUpstream(GatewayExchange exchange)
    {
        exchange.request().headers().set(name, value);
    }
}
