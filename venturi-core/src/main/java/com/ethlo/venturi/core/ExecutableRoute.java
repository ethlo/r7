package com.ethlo.venturi.core;

import com.ethlo.venturi.api.GatewayExchange;
import com.ethlo.venturi.api.GatewayRoute;

public interface ExecutableRoute extends GatewayRoute
{
    /**
     * Executes the lifecycle: Filters -> Terminal Action.
     */
    void execute(GatewayExchange exchange) throws Exception;
}