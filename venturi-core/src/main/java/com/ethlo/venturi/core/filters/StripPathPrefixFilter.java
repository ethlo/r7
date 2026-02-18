package com.ethlo.venturi.core.filters;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.core.ShortInfo;

public class StripPathPrefixFilter implements GatewayFilter, ShortInfo
{
    private final int parts;

    public StripPathPrefixFilter(StripPathPrefixConfig config)
    {
        this.parts = config.parts();
    }

    @Override
    public void beforeUpstream(GatewayExchange exchange)
    {
        final String path = exchange.request().path().toString();
        if (parts <= 0) return;

        int pos = 0;
        for (int i = 0; i < parts; i++)
        {
            pos = path.indexOf("/", pos + 1);
            if (pos == -1)
            {
                // We've run out of slashes.
                // If we're at "/v1" and stripping 1, we should result in "/"
                exchange.request().path("/");
                return;
            }
        }

        String newPath = path.substring(pos);
        // Ensure we don't return an empty string if the path ended exactly at the slash
        exchange.request().path(newPath.isEmpty() ? "/" : newPath);
    }

    @Override
    public String summary()
    {
        return String.format("StripPathPrefix: %s", parts == 1 ? parts + " part" : parts + " parts");
    }
}