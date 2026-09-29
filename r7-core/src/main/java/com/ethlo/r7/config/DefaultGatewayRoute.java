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
    private final List<GatewayFilter> globalFilters;
    private final List<GatewayFilter> carriedFilters;
    private final List<GatewayFilter> ownFilters;
    private final int globalClientRequestFilterCount;
    private final Map<String, DefaultGatewayRoute> fallbacksOfThis = new ConcurrentHashMap<>();

    public DefaultGatewayRoute(final List<String> uri, final GatewayPredicate predicate, final List<GatewayFilter> filters, final RouteJournalConfig journal, final RouteDefinition routeDefinition)
    {
        this(uri, predicate, filters, 0, journal, routeDefinition);
    }

    /**
     * @param globalFilterCount how many leading entries of {@code filters} are the global filters
     */
    public DefaultGatewayRoute(final List<String> uri, final GatewayPredicate predicate, final List<GatewayFilter> filters, final int globalFilterCount, final RouteJournalConfig journal, final RouteDefinition routeDefinition)
    {
        this(uri, predicate, filters.subList(0, globalFilterCount), List.of(), filters.subList(globalFilterCount, filters.size()), journal, routeDefinition);
    }

    /**
     * @param globalFilters  the global filter instances, run in every phase
     * @param carriedFilters filters of routes the request already passed through before falling
     *                       back to this one: their request phase has run, so their upstream,
     *                       response and completion phases remain
     * @param ownFilters     this route's own filters, run in every phase
     */
    private DefaultGatewayRoute(final List<String> uri, final GatewayPredicate predicate, final List<GatewayFilter> globalFilters, final List<GatewayFilter> carriedFilters, final List<GatewayFilter> ownFilters, final RouteJournalConfig journal, final RouteDefinition routeDefinition)
    {
        this.id = routeDefinition.id();
        this.uri = uri;
        this.predicate = predicate;
        this.journal = journal;
        this.routeDefinition = routeDefinition;
        this.globalFilters = List.copyOf(globalFilters);
        this.carriedFilters = List.copyOf(carriedFilters);
        this.ownFilters = List.copyOf(ownFilters);

        final List<GatewayFilter> requestPhases = concat(this.globalFilters, this.ownFilters);
        final List<GatewayFilter> allPhases = concat(concat(this.globalFilters, this.carriedFilters), this.ownFilters);
        this.filters = allPhases;

        this.clientRequestFilters = ofType(requestPhases, ClientRequestGatewayFilter.class, new ClientRequestGatewayFilter[0]);
        this.beforeUpstreamGatewayFilters = ofType(allPhases, UpstreamRequestGatewayFilter.class, new UpstreamRequestGatewayFilter[0]);
        this.beforeCommitGatewayFilters = ofType(allPhases, ClientResponseGatewayFilter.class, new ClientResponseGatewayFilter[0]);
        this.completedGatewayFilters = ofType(allPhases, CompletedGatewayFilter.class, new CompletedGatewayFilter[0]);

        this.globalClientRequestFilterCount = (int) this.globalFilters.stream()
                .filter(f -> f instanceof ClientRequestGatewayFilter)
                .count();
    }

    private static List<GatewayFilter> concat(final List<GatewayFilter> a, final List<GatewayFilter> b)
    {
        final List<GatewayFilter> result = new ArrayList<>(a.size() + b.size());
        result.addAll(a);
        result.addAll(b);
        return List.copyOf(result);
    }

    private static <T> T[] ofType(final List<GatewayFilter> filters, final Class<T> type, final T[] empty)
    {
        return filters.stream().filter(type::isInstance).map(type::cast).toList().toArray(empty);
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
     * Number of leading {@link #filters()} that are instances of the global filters.
     */
    public int globalFilterCount()
    {
        return this.globalFilters.size();
    }

    /**
     * {@code fallback} as it must run for a request that first matched this route.
     * <p>
     * The rule: every filter whose request phase ran gets its later phases, on the same instance,
     * and no filter gets a later phase without its request phase. Several filters keep state
     * across phases - SimpleMetrics counts a request active when it starts and done when it
     * completes, CircuitBreaker and RateLimiter pair a decision with a response - so a response
     * or completion phase on a different instance, or with no request phase before it, corrupts
     * that state. Hence:
     * <ul>
     *   <li>global filters: this route's instances, in every phase (global filters are
     *   instantiated per route, and their request phase ran on these);</li>
     *   <li>this route's own request filters, and whatever this route already carried: every
     *   later phase - upstream, response, completion. A filter that started on the request owns
     *   what follows from it: BasicAuth, for one, removes the client's verified credentials in
     *   its upstream phase, and must do so whichever upstream the request ends up at;</li>
     *   <li>this route's filters that only begin at the upstream phase never run: they shaped the
     *   request for this route's upstream (InjectBasicAuth, SetRequestHeader, rewrites), which is
     *   exactly what must not reach another;</li>
     *   <li>the fallback's own filters: every phase.</li>
     * </ul>
     * Built once per fallback and kept with this route, so a reload discards it along with the
     * route, and a chain a -> b -> c carries everything that started along the way.
     */
    public DefaultGatewayRoute asFallbackOfThis(final DefaultGatewayRoute fallback)
    {
        return this.fallbacksOfThis.computeIfAbsent(fallback.id(), ignored ->
        {
            final List<GatewayFilter> started = this.ownFilters.stream()
                    .filter(f -> f instanceof ClientRequestGatewayFilter)
                    .toList();
            return new DefaultGatewayRoute(fallback.uri, fallback.predicate, this.globalFilters, concat(this.carriedFilters, started), fallback.ownFilters, fallback.journal, fallback.routeDefinition);
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
