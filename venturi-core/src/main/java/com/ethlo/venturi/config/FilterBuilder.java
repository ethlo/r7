package com.ethlo.venturi.config;

import java.util.ArrayList;
import java.util.List;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.constants.VenturiConstants;
import com.ethlo.venturi.core.AuditLevel;
import com.ethlo.venturi.core.filters.CorrelationIdFilter;
import com.ethlo.venturi.core.filters.RequireAuthorizationHeaderFilter;
import com.ethlo.venturi.core.filters.StripCacheHeadersFilter;

public class FilterBuilder
{

    public List<GatewayFilter> build(RouteDefinition routeDef)
    {
        List<GatewayFilter> filters = new ArrayList<>();

        // Always inject the Audit Filter first to stamp the exchange
        filters.add(createAuditFilter(routeDef.audit()));

        // Add user-defined filters from YAML
        if (routeDef.filters() != null)
        {
            for (FilterDefinition f : routeDef.filters())
            {
                filters.add(resolveFilter(f));
            }
        }

        return filters;
    }

    private GatewayFilter createAuditFilter(AuditDefinition audit)
    {
        final AuditLevel req = audit.request;
        final AuditLevel res = audit.response;

        return new GatewayFilter()
        {
            @Override
            public void beforeUpstream(GatewayExchange exchange)
            {
                // Stamp the requirements onto the exchange attributes
                exchange.attributes().put(VenturiConstants.AUDIT_LEVEL_REQUEST, req);
                exchange.attributes().put(VenturiConstants.AUDIT_LEVEL_RESPONSE, res);
            }
        };
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