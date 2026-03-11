package com.ethlo.r7.status;

import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.CompletedGatewayFilter;
import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.core.ShortInfo;
import com.ethlo.r7.status.dto.FilterNode;

public final class PipelineVisualizer
{
    public static FilterNode buildNestedVisualization(final GatewayFilter[] routeFilters)
    {
        // 1. The innermost core of the onion is always the Upstream Proxy
        FilterNode currentNode = new FilterNode("UpstreamProxy", false, false, false, false, null);

        // 2. Iterate backward through the array, wrapping from the inside out
        for (int i = routeFilters.length - 1; i >= 0; i--)
        {
            final GatewayFilter filter = routeFilters[i];

            final boolean hasClientReq = filter instanceof ClientRequestGatewayFilter;
            final boolean hasUpstreamReq = filter instanceof UpstreamRequestGatewayFilter;
            final boolean hasClientRes = filter instanceof ClientResponseGatewayFilter;
            final boolean hasCompleted = filter instanceof CompletedGatewayFilter;

            currentNode = new FilterNode(
                    filter instanceof ShortInfo shortInfo ? shortInfo.summary() : filter.getClass().getName(),
                    hasClientReq,
                    hasUpstreamReq,
                    hasClientRes,
                    hasCompleted,
                    currentNode
            );
        }

        // 3. The final currentNode is the outermost wrapper (index 0)
        return currentNode;
    }
}