package com.ethlo.r7.config;

import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;

import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.CompletedGatewayFilter;
import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRoute;

public class DefaultGatewayRoute implements GatewayRoute
{
    private final CharSequence id;
    private final List<CharSequence> uri;
    private final GatewayPredicate predicate;
    private final List<GatewayFilter> filters;
    private final RouteJournalConfig journal;
    private final RouteDefinition routeDefinition;
    private final ClientRequestGatewayFilter[] clientRequestFilters;
    private final CompletedGatewayFilter[] completedGatewayFilters;
    private final ClientResponseGatewayFilter[] beforeCommitGatewayFilters;
    private final UpstreamRequestGatewayFilter[] beforeUpstreamGatewayFilters;

    public DefaultGatewayRoute(final List<CharSequence> uri, final GatewayPredicate predicate, final List<GatewayFilter> filters, final RouteJournalConfig journal, final RouteDefinition routeDefinition)
    {
        this.id = routeDefinition.id();
        this.uri = uri;
        this.predicate = predicate;
        this.filters = filters;
        this.journal = journal;
        this.routeDefinition = routeDefinition;

        this.clientRequestFilters = filters.stream().filter(f -> f instanceof ClientRequestGatewayFilter)
                .map(ClientRequestGatewayFilter.class::cast)
                .toList()
                .toArray(new ClientRequestGatewayFilter[0]);

        this.beforeUpstreamGatewayFilters = filters.stream().filter(f -> f instanceof UpstreamRequestGatewayFilter)
                .map(UpstreamRequestGatewayFilter.class::cast)
                .toList()
                .toArray(new UpstreamRequestGatewayFilter[0]);


        this.beforeCommitGatewayFilters = filters.stream().filter(f -> f instanceof ClientResponseGatewayFilter)
                .map(ClientResponseGatewayFilter.class::cast)
                .toList()
                .toArray(new ClientResponseGatewayFilter[0]);

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

    public ClientResponseGatewayFilter[] beforeCommitGatewayFilters()
    {
        return beforeCommitGatewayFilters;
    }

    public UpstreamRequestGatewayFilter[] beforeUpstreamGatewayFilters()
    {
        return beforeUpstreamGatewayFilters;
    }

    public RouteDefinition routeDefinition()
    {
        return routeDefinition;
    }

    @Override
    public String toString()
    {
        return new StringJoiner(", ", DefaultGatewayRoute.class.getSimpleName() + "[", "]")
                .add("id=" + id)
                .add("uri=" + uri)
                .add("predicate=" + predicate)
                .add("globalFilters=" + filters)
                .add("routeDefinition=" + routeDefinition)
                .add("clientRequestFilters=" + Arrays.toString(clientRequestFilters))
                .add("completedGatewayFilters=" + Arrays.toString(completedGatewayFilters))
                .add("beforeCommitGatewayFilters=" + Arrays.toString(beforeCommitGatewayFilters))
                .add("beforeUpstreamGatewayFilters=" + Arrays.toString(beforeUpstreamGatewayFilters))
                .toString();
    }

    public RouteJournalConfig journal()
    {
        return journal;
    }
}
