package com.ethlo.venturi.config;

import java.util.List;

import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRoute;

public record DefaultGatewayRoute(CharSequence id, List<CharSequence> uri, GatewayPredicate predicate,
                                  List<GatewayFilter> filters, RouteDefinition routeDefinition) implements GatewayRoute
{

}
