package com.ethlo.r7.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.CompletedGatewayFilter;
import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;

public class DefaultGatewayRoute implements GatewayRoute
{
    private final String id;
    private final List<String> uri;
    private final GatewayPredicate predicate;
    private final List<GatewayFilter> filters;
    private final RouteJournalConfig journal;
    private final RouteDefinition routeDefinition;
    private final ClientRequestGatewayFilter[] clientRequestFilters;
    private final CompletedGatewayFilter[] completedGatewayFilters;
    private final ClientResponseGatewayFilter[] beforeCommitGatewayFilters;
    private final UpstreamRequestGatewayFilter[] beforeUpstreamGatewayFilters;
    private final int globalFilterCount;
    private final int globalClientRequestFilterCount;
    private final Map<String, DefaultGatewayRoute> fallbacksWithOurGlobals = new ConcurrentHashMap<>();

    public DefaultGatewayRoute(final List<String> uri, final GatewayPredicate predicate, final List<GatewayFilter> filters, final RouteJournalConfig journal, final RouteDefinition routeDefinition)
    {
        this(uri, predicate, filters, 0, journal, routeDefinition);
    }

    /**
     * @param globalFilterCount how many leading entries of {@code filters} are the global filters
     */
    public DefaultGatewayRoute(final List<String> uri, final GatewayPredicate predicate, final List<GatewayFilter> filters, final int globalFilterCount, final RouteJournalConfig journal, final RouteDefinition routeDefinition)
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

        this.globalFilterCount = globalFilterCount;
        this.globalClientRequestFilterCount = (int) filters.subList(0, globalFilterCount).stream()
                .filter(f -> f instanceof ClientRequestGatewayFilter)
                .count();
    }

    /**
     * Number of leading {@link #clientRequestFilters()} that are the global filters. A request
     * that reaches a route as a fallback has already been through the global filters' request
     * phase, so it starts after these: running them twice would, for example, charge a global
     * rate limit twice for one request.
     */
    public int globalClientRequestFilterCount()
    {
        return globalClientRequestFilterCount;
    }

    /**
     * {@code fallback} as it must run for a request that first matched this route: the
     * fallback's own filters, but this route's instances of the global filters.
     * <p>
     * Global filters are instantiated per route, and several keep state across phases -
     * SimpleMetrics counts a request active in its request phase and done in its completion
     * phase, CircuitBreaker and RateLimiter pair a request decision with a response. Their
     * request phase has already run on this route's instances, so the response and completion
     * phases must run on the same ones; the fallback's copies would see a request end that never
     * began. Built once per fallback and kept with this route, so a reload discards it along
     * with the route, and a chain a -> b -> c carries a's instances all the way down.
     */
    public DefaultGatewayRoute asFallbackOfThis(final DefaultGatewayRoute fallback)
    {
        return this.fallbacksWithOurGlobals.computeIfAbsent(fallback.id(), ignored ->
        {
            final List<GatewayFilter> combined = new ArrayList<>(this.filters.subList(0, this.globalFilterCount));
            combined.addAll(fallback.filters.subList(fallback.globalFilterCount, fallback.filters.size()));
            return new DefaultGatewayRoute(fallback.uri, fallback.predicate, combined, this.globalFilterCount, fallback.journal, fallback.routeDefinition);
        });
    }


    @Override
    public String id()
    {
        return id;
    }

    @Override
    public List<String> uri()
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
