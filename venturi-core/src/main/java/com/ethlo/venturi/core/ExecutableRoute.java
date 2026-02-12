package com.ethlo.venturi.core;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.config.RouteDefinition;

public interface ExecutableRoute extends GatewayRoute
{
    RouteDefinition routeDefinition();

    /**
     * Executes the lifecycle: Filters -> Terminal Action.
     */
    void execute(GatewayExchange exchange) throws Exception;
}