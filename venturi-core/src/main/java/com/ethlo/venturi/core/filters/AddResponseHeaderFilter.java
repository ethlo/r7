package com.ethlo.venturi.core.filters;

import java.util.Map;

import com.ethlo.venturi.RedactUtil;
import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.core.ShortInfo;

public class AddResponseHeaderFilter implements GatewayFilter, ShortInfo
{
    private final CharSequence name;
    private final CharSequence value;

    public AddResponseHeaderFilter(Map<String, String> args)
    {
        this.name = args.get("name");
        this.value = args.get("value");
    }

    @Override
    public void beforeUpstream(GatewayExchange exchange)
    {
        exchange.response().headers().set(name, value);
    }

    @Override
    public String summary()
    {
        return "AddResponseHeader: " + name + ": " + RedactUtil.redact(value.toString(), 1);
    }
}
