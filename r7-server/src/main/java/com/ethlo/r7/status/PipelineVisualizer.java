package com.ethlo.r7.status;

import com.ethlo.r7.util.SensitiveConfig;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ComponentStatus;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.CompletedGatewayFilter;
import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import java.util.Locale;
import java.util.function.IntFunction;

import com.ethlo.r7.config.DefaultGatewayRoute;
import com.ethlo.r7.config.UpstreamConfig;
import com.ethlo.r7.status.dto.FilterNode;

public final class PipelineVisualizer
{
    public static FilterNode buildNestedVisualization(final UpstreamConfig upstreamConfig, final ComponentStatus upstreamStatus, final GatewayFilter[] routeFilters, final IntFunction<DefaultGatewayRoute.Level> levels)
    {
        // The innermost core of the onion
        FilterNode currentNode = new FilterNode("upstream", upstreamConfig != null ? SensitiveConfig.redactUrlCredentials(upstreamConfig.toString()) : "None", "route", false, false, false, false, upstreamStatus, null);

        // Iterate backward through the array, wrapping from the inside out
        for (int i = routeFilters.length - 1; i >= 0; i--)
        {
            final GatewayFilter filter = routeFilters[i];

            final boolean hasClientReq = filter instanceof ClientRequestGatewayFilter;
            final boolean hasUpstreamReq = filter instanceof UpstreamRequestGatewayFilter;
            final boolean hasClientRes = filter instanceof ClientResponseGatewayFilter;
            final boolean hasCompleted = filter instanceof CompletedGatewayFilter;

            currentNode = new FilterNode(
                    filter.name(),
                    filter.summary(),
                    levels.apply(i).name().toLowerCase(Locale.ROOT),
                    hasClientReq,
                    hasUpstreamReq,
                    hasClientRes,
                    hasCompleted,
                    ComponentStatuses.of(filter),
                    currentNode
            );
        }

        // The final currentNode is the outermost wrapper (index 0)
        return currentNode;
    }
}