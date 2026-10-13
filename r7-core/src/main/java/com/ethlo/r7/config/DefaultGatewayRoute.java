package com.ethlo.r7.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.CompletedGatewayFilter;
import com.ethlo.r7.api.FallbackAwareGatewayFilter;
import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.api.StateKey;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;

public class DefaultGatewayRoute implements GatewayRoute
{
    /**
     * Where a filter of a route's chain was declared: {@link #filterLevel(int)}.
     */
    /**
     * Where something a route uses was declared in routes.yaml: at the root, on the route's
     * group, or on the route itself. What is declared above a route is one instance for every
     * route beneath that declaration.
     */
    public enum Level
    {
        ROOT, GROUP, ROUTE
    }

    private final String id;
    private final String group;
    private final Level upstreamLevel;
    private final List<String> uri;
    private final GatewayPredicate predicate;
    private final List<GatewayFilter> filters;
    private final RouteJournalConfig journal;
    private final RouteDefinition routeDefinition;
    private final ClientRequestGatewayFilter[] clientRequestFilters;
    private final CompletedGatewayFilter[] completedGatewayFilters;
    private final FallbackAwareGatewayFilter[] startedFallbackAwareFilters;
    private final ClientResponseGatewayFilter[] beforeCommitGatewayFilters;
    private final UpstreamRequestGatewayFilter[] beforeUpstreamGatewayFilters;
    private final int rootFilterCount;
    private final int groupFilterCount;
    private final int startedClientRequestFilterCount;
    private final Map<String, DefaultGatewayRoute> fallbacksOfThis = new ConcurrentHashMap<>();
    private final Map<StateKey<?>, Object> attachments;

    public DefaultGatewayRoute(final List<String> uri, final GatewayPredicate predicate, final List<GatewayFilter> filters, final RouteJournalConfig journal, final RouteDefinition routeDefinition)
    {
        this(uri, predicate, filters, 0, journal, routeDefinition);
    }

    /**
     * @param rootFilterCount how many leading entries of {@code filters} are the root's
     */
    public DefaultGatewayRoute(final List<String> uri, final GatewayPredicate predicate, final List<GatewayFilter> filters, final int rootFilterCount, final RouteJournalConfig journal, final RouteDefinition routeDefinition)
    {
        this(uri, predicate, filters, rootFilterCount, 0, null, journal, routeDefinition);
    }

    /**
     * @param filters          the route's chain in the order it runs on the request: the root's
     *                         filters, then its group's, then its own
     * @param rootFilterCount  how many leading entries of {@code filters} are the root's
     * @param groupFilterCount how many entries after those are its group's
     * @param group            the id of the group the route is declared in, or {@code null}
     */
    public DefaultGatewayRoute(final List<String> uri, final GatewayPredicate predicate, final List<GatewayFilter> filters, final int rootFilterCount, final int groupFilterCount, final String group, final RouteJournalConfig journal, final RouteDefinition routeDefinition)
    {
        this(uri, predicate, filters, rootFilterCount, groupFilterCount, group, Level.ROUTE, journal, routeDefinition);
    }

    /**
     * @param upstreamLevel the level every key of the route's effective upstream comes from: the
     *                      route if it names any, else its group if that names any, else the
     *                      root. Routes with one upstream level and owner share one upstream.
     */
    public DefaultGatewayRoute(final List<String> uri, final GatewayPredicate predicate, final List<GatewayFilter> filters, final int rootFilterCount, final int groupFilterCount, final String group, final Level upstreamLevel, final RouteJournalConfig journal, final RouteDefinition routeDefinition)
    {
        this(uri, predicate, filters, List.of(), rootFilterCount, groupFilterCount, group, upstreamLevel, journal, routeDefinition, new ConcurrentHashMap<>());
    }

    /**
     * @param filters     every filter with a phase left for the exchange, in chain order
     * @param started     the leading entries of {@code filters} whose request phase already ran,
     *                    on the route that fell back to this one; empty for a matched route
     * @param attachments shared with the route this one is derived from, if any
     */
    private DefaultGatewayRoute(final List<String> uri, final GatewayPredicate predicate, final List<GatewayFilter> filters, final List<GatewayFilter> started, final int rootFilterCount, final int groupFilterCount, final String group, final Level upstreamLevel, final RouteJournalConfig journal, final RouteDefinition routeDefinition, final Map<StateKey<?>, Object> attachments)
    {
        this.attachments = attachments;
        this.id = routeDefinition.id();
        this.group = group;
        this.upstreamLevel = upstreamLevel;
        this.uri = uri;
        this.predicate = predicate;
        this.journal = journal;
        this.routeDefinition = routeDefinition;
        this.filters = List.copyOf(filters);
        this.rootFilterCount = rootFilterCount;
        this.groupFilterCount = groupFilterCount;

        this.clientRequestFilters = ofType(this.filters, ClientRequestGatewayFilter.class, new ClientRequestGatewayFilter[0]);
        this.beforeUpstreamGatewayFilters = ofType(this.filters, UpstreamRequestGatewayFilter.class, new UpstreamRequestGatewayFilter[0]);
        this.beforeCommitGatewayFilters = ofType(this.filters, ClientResponseGatewayFilter.class, new ClientResponseGatewayFilter[0]);
        this.completedGatewayFilters = ofType(this.filters, CompletedGatewayFilter.class, new CompletedGatewayFilter[0]);

        this.startedClientRequestFilterCount = ofType(started, ClientRequestGatewayFilter.class, new ClientRequestGatewayFilter[0]).length;
        this.startedFallbackAwareFilters = started.stream()
                .filter(ClientRequestGatewayFilter.class::isInstance)
                .filter(FallbackAwareGatewayFilter.class::isInstance)
                .map(FallbackAwareGatewayFilter.class::cast)
                .toArray(FallbackAwareGatewayFilter[]::new);
    }

    private static <T> T[] ofType(final List<GatewayFilter> filters, final Class<T> type, final T[] empty)
    {
        return filters.stream().filter(type::isInstance).map(type::cast).toList().toArray(empty);
    }

    /**
     * Number of leading {@link #clientRequestFilters()} whose request phase already ran: nonzero
     * only for a route built by {@link #asFallbackOfThis}. A request handed to a fallback starts
     * after these: running them again would, for example, charge a rate limit twice for one
     * request.
     */
    public int startedClientRequestFilterCount()
    {
        return this.startedClientRequestFilterCount;
    }

    /**
     * @return where the filter at {@code index} of {@link #filters()} was declared, for a route
     * as loaded (not one built by {@link #asFallbackOfThis})
     */
    public Level filterLevel(final int index)
    {
        if (index < this.rootFilterCount)
        {
            return Level.ROOT;
        }
        return index < this.rootFilterCount + this.groupFilterCount ? Level.GROUP : Level.ROUTE;
    }

    /**
     * @return the id of the group this route is declared in, or {@code null} for a route at the
     * top level
     */
    public String group()
    {
        return this.group;
    }

    /**
     * @return the level the route's upstream was declared on; {@link Level#ROUTE} for a route
     * without an upstream
     */
    public Level upstreamLevel()
    {
        return this.upstreamLevel;
    }

    /**
     * @return what identifies the route's upstream within one loaded configuration: routes with
     * the same key inherit every upstream key from the same declarations, and share one upstream
     * (one connection pool, one health state). Group and route ids are one namespace.
     */
    public String upstreamKey()
    {
        return switch (this.upstreamLevel)
        {
            case ROOT -> "root";
            case GROUP -> "group:" + this.group;
            case ROUTE -> "route:" + this.id;
        };
    }

    /**
     * {@code fallback} as it must run for a request that first matched this route.
     * <p>
     * The rule: every filter whose request phase ran gets its later phases, on the same instance,
     * and no filter gets a later phase without its request phase, or its request phase twice.
     * Several filters keep state across phases - SimpleMetrics counts a request active when it
     * starts and done when it completes, CircuitBreaker and RateLimiter pair a decision with a
     * response - so a response or completion phase on a different instance, or with no request
     * phase before it, corrupts that state. Filters are shared by instance (one per declaration:
     * the root's for every route, a group's for its routes), so "already ran" is a question of
     * identity, never of level. Hence:
     * <ul>
     *   <li>every request filter of this route's chain, from any level, and whatever this route
     *   already carried: every later phase - upstream, response, completion - and no request
     *   phase again. A filter that started on the request owns what follows from it: BasicAuth,
     *   for one, removes the client's verified credentials in its upstream phase, and must do so
     *   whichever upstream the request ends up at;</li>
     *   <li>this route's filters that only begin at the upstream phase run only where the
     *   fallback's chain has the same instance (a root's, or a shared group's): otherwise they
     *   shaped the request for this route's upstream (InjectBasicAuth, SetRequestHeader,
     *   rewrites), which is exactly what must not reach another;</li>
     *   <li>the rest of the fallback's chain - its group's, when that is another group, and its
     *   own - every phase, after the above.</li>
     * </ul>
     * Built once per fallback and kept with this route, so a reload discards it along with the
     * route, and a chain a -> b -> c carries everything that started along the way.
     */
    public DefaultGatewayRoute asFallbackOfThis(final DefaultGatewayRoute fallback)
    {
        return this.fallbacksOfThis.computeIfAbsent(fallback.id(), ignored ->
        {
            final Set<GatewayFilter> started = identitySet(this.filters.stream().filter(ClientRequestGatewayFilter.class::isInstance).toList());
            final Set<GatewayFilter> fallbackChain = identitySet(fallback.filters);

            final List<GatewayFilter> carried = this.filters.stream()
                    .filter(f -> started.contains(f) || fallbackChain.contains(f))
                    .toList();
            final Set<GatewayFilter> included = identitySet(carried);
            final List<GatewayFilter> chain = new ArrayList<>(carried);
            fallback.filters.stream().filter(f -> !included.contains(f)).forEach(chain::add);

            // The started request filters lead the chain: every one of them is in 'carried', and
            // nothing in 'carried' is a request filter that has not started.
            final List<GatewayFilter> startedInOrder = carried.stream().filter(started::contains).toList();
            return new DefaultGatewayRoute(fallback.uri, fallback.predicate, chain, startedInOrder, 0, 0, fallback.group, fallback.upstreamLevel, fallback.journal, fallback.routeDefinition, fallback.attachments);
        });
    }

    private static Set<GatewayFilter> identitySet(final List<GatewayFilter> filters)
    {
        final Set<GatewayFilter> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(filters);
        return set;
    }

    /**
     * Attaches engine state to this route instance, and to every fallback copy of it, so that it
     * is published and retired with the route (see {@link RouteGenerationListener}). Meant to be
     * called before the route is published.
     */
    public <T> void attach(final StateKey<T> key, final T value)
    {
        this.attachments.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T attachment(final StateKey<T> key)
    {
        return (T) this.attachments.get(key);
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

    /**
     * The filters to tell that the request fell back to this route, when this is a fallback route
     * built by {@link #asFallbackOfThis}: those whose request phase ran on the route that fell
     * back, from whichever level they were declared on.
     */
    public FallbackAwareGatewayFilter[] startedFallbackAwareFilters()
    {
        return startedFallbackAwareFilters;
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
                .add("group=" + group)
                .add("filters=" + filters)
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
