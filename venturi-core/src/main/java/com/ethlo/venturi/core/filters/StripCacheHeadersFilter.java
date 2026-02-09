package com.ethlo.venturi.core.filters;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.constants.HttpHeaders;

public class StripCacheHeadersFilter implements GatewayFilter
{
    @Override
    public void beforeUpstream(final GatewayExchange exchange)
    {
        // Remove headers that trigger a 304 response
        exchange.request().headers().remove(HttpHeaders.IF_MODIFIED_SINCE);
        exchange.request().headers().remove(HttpHeaders.IF_NONE_MATCH);

        // Optional: Force the backend to not cache the response at all
        exchange.request().headers().set(HttpHeaders.CACHE_CONTROL, "no-cache");
        exchange.request().headers().set(HttpHeaders.PRAGMA, "no-cache");
    }
}
