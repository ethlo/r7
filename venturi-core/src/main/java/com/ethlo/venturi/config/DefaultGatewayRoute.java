package com.ethlo.venturi.config;

import java.util.List;

import com.ethlo.venturi.api.BeforeCommitGatewayFilter;
import com.ethlo.venturi.api.BeforeUpstreamGatewayFilter;
import com.ethlo.venturi.api.ClientRequestGatewayFilter;
import com.ethlo.venturi.api.CompletedGatewayFilter;
import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRoute;

public class DefaultGatewayRoute implements GatewayRoute
{
    private final CharSequence id;
    private final List<CharSequence> uri;
    private final GatewayPredicate predicate;
    private final List<GatewayFilter> filters;
    private final RouteDefinition routeDefinition;
    private final ClientRequestGatewayFilter[] clientRequestFilters;
    private final CompletedGatewayFilter[] completedGatewayFilters;
    private final BeforeCommitGatewayFilter[] beforeCommitGatewayFilters;
    private final BeforeUpstreamGatewayFilter[] beforeUpstreamGatewayFilters;

    public DefaultGatewayRoute(final CharSequence id, final List<CharSequence> uri, final GatewayPredicate predicate, final List<GatewayFilter> filters, final RouteDefinition routeDefinition)
    {
        this.id = id;
        this.uri = uri;
        this.predicate = predicate;
        this.filters = filters;
        this.routeDefinition = routeDefinition;

        this.clientRequestFilters = filters.stream().filter(f -> f instanceof ClientRequestGatewayFilter)
                .map(ClientRequestGatewayFilter.class::cast)
                .toList()
                .toArray(new ClientRequestGatewayFilter[0]);

        this.beforeUpstreamGatewayFilters = filters.stream().filter(f -> f instanceof BeforeUpstreamGatewayFilter)
                .map(BeforeUpstreamGatewayFilter.class::cast)
                .toList()
                .toArray(new BeforeUpstreamGatewayFilter[0]);


        this.beforeCommitGatewayFilters = filters.stream().filter(f -> f instanceof BeforeCommitGatewayFilter)
                .map(BeforeCommitGatewayFilter.class::cast)
                .toList()
                .toArray(new BeforeCommitGatewayFilter[0]);

        this.completedGatewayFilters = filters.stream().filter(f -> f instanceof CompletedGatewayFilter)
                .map(CompletedGatewayFilter.class::cast)
                .toList()
                .toArray(new CompletedGatewayFilter[0]);

    }


    @Override
    public CharSequence id()
    {
        return id;
    }

    @Override
    public List<CharSequence> uri()
    {
        return uri;
    }

    @Override
    public GatewayPredicate predicate()
    {
        return predicate;
    }

    @Override
    public List<GatewayFilter> filters()
    {
        return filters;
    }

    public ClientRequestGatewayFilter[] clientRequestFilters()
    {
        return clientRequestFilters;
    }

    public CompletedGatewayFilter[] completedGatewayFilters()
    {
        return completedGatewayFilters;
    }

    public BeforeCommitGatewayFilter[] beforeCommitGatewayFilters()
    {
        return beforeCommitGatewayFilters;
    }

    public BeforeUpstreamGatewayFilter[] beforeUpstreamGatewayFilters()
    {
        return beforeUpstreamGatewayFilters;
    }

    public RouteDefinition routeDefinition()
    {
        return routeDefinition;
    }
}
