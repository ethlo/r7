package com.ethlo.venturi.core.filters;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;

public class RewritePathFilter implements GatewayFilter
{
    private final Pattern regexp;
    private final String replacement;

    public RewritePathFilter(RewritePathConfig config)
    {
        this.regexp = Pattern.compile(config.regexp());
        this.replacement = config.replacement();
    }

    @Override
    public void beforeUpstream(GatewayExchange exchange)
    {
        final CharSequence path = exchange.request().path();
        final Matcher matcher = regexp.matcher(path);

        if (matcher.find())
        {
            final String newPath = matcher.replaceAll(replacement);
            exchange.request().path(newPath);
        }
    }
}