package com.ethlo.venturi.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.core.filters.AddRequestHeaderFilter;
import com.ethlo.venturi.core.filters.AddResponseHeaderFilter;
import com.ethlo.venturi.core.filters.CorrelationIdFilter;
import com.ethlo.venturi.core.filters.RequireAuthorizationHeaderFilter;
import com.ethlo.venturi.core.filters.RewritePathConfig;
import com.ethlo.venturi.core.filters.RewritePathFilter;
import com.ethlo.venturi.core.filters.StripCacheHeadersFilter;
import com.ethlo.venturi.core.filters.StripPrefixConfig;
import com.ethlo.venturi.core.filters.StripPrefixFilter;
import com.ethlo.venturi.validation.Validatable;
import com.ethlo.venturi.validation.ValidationResult;

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
        return switch (def.type())
        {
            case "AddRequestHeader" -> new AddRequestHeaderFilter(def.args());
            case "AddResponseHeader" -> new AddResponseHeaderFilter(def.args());
            case "CorrelationIdHeader" -> new CorrelationIdFilter();
            case "StripCacheHeaders" -> new StripCacheHeadersFilter();
            case "RequireAuthorizationHeader" -> new RequireAuthorizationHeaderFilter();
            case "RewritePath" -> new RewritePathFilter(loadConfig(def.args(), RewritePathConfig.class));
            case "StripPrefix" -> new StripPrefixFilter(loadConfig(def.args(), StripPrefixConfig.class));
            default -> throw new IllegalArgumentException("Unknown filter type: " + def.type());
        };
    }

    private <T> T loadConfig(Map<String, String> args, Class<T> configType)
    {
        final T config = VenturiLoader.convertValue(args, configType);
        if (config instanceof Validatable validatable)
        {
            final ValidationResult result = new ValidationResult();
            validatable.validate(result);
            result.throwIfInvalid();
        }
        return config;
    }
}