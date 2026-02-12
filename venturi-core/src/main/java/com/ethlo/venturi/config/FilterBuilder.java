package com.ethlo.venturi.config;

import java.util.ArrayList;
import java.util.List;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.core.filters.CorrelationIdFilter;
import com.ethlo.venturi.core.filters.RequireAuthorizationHeaderFilter;
import com.ethlo.venturi.core.filters.StripCacheHeadersFilter;

public class FilterBuilder
{

    public List<GatewayFilter> resolve(RouteDefinition routeDef)
    {
        final List<GatewayFilter> filters = new ArrayList<>();

        if (routeDef.filters() != null)
        {
            for (FilterDefinition f : routeDef.filters())
            {
                filters.add(resolveFilter(f));
            }
        }

        return filters;
    }

    public GatewayFilter resolveFilter(FilterDefinition def)
    {
        if ("AddRequestHeader".equalsIgnoreCase(def.type()))
        {
            String name = def.args().get("name");
            String value = def.args().get("value");
            return new GatewayFilter()
            {
                @Override
                public void beforeUpstream(GatewayExchange exchange)
                {
                    exchange.request().headers().set(name, value);
                }
            };
        }
        else if ("AddResponseHeader".equalsIgnoreCase(def.type()))
        {
            String name = def.args().get("name");
            String value = def.args().get("value");
            return new GatewayFilter()
            {
                @Override
                public void beforeUpstream(GatewayExchange exchange)
                {
                    exchange.response().headers().set(name, value);
                }
            };
        }
        else if ("CorrelationIdHeader".equalsIgnoreCase(def.type()))
        {
            return new CorrelationIdFilter();
        }
        else if ("StripCacheHeaders".equalsIgnoreCase(def.type()))
        {
            return new StripCacheHeadersFilter();
        }
        else if ("RequireAuthorizationHeader".equalsIgnoreCase(def.type()))
        {
            return new RequireAuthorizationHeaderFilter();
        }

        // Add more filter types here (e.g., RewritePath, RateLimit)
        throw new IllegalArgumentException("Unknown filter type: " + def.type());
    }
}