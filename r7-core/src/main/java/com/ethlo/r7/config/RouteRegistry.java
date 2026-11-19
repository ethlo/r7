package com.ethlo.r7.config;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.condition.Condition;
import com.ethlo.r7.condition.ConditionInput;

public class RouteRegistry
{
    private final AtomicReference<Snapshot> current = new AtomicReference<>(new Snapshot(null, List.of(), null));

    /**
     * One entry of the routing table, tested in order: a route, or a group of routes.
     */
    public sealed interface RoutingEntry permits Single, Group
    {
    }

    /**
     * A route at the top level, matched by its own condition.
     */
    public record Single(GatewayRoute route, Condition match) implements RoutingEntry
    {
        /**
         * A route's own entry: its {@link DefaultGatewayRoute#match()}; another implementation
         * of {@link GatewayRoute} is reached only by id, as a fallback.
         */
        public Single(final GatewayRoute route)
        {
            this(route, route instanceof DefaultGatewayRoute defaultRoute ? defaultRoute.match() : Condition.FALSE);
        }
    }

    /**
     * A group's routes behind the group's own condition, tested once: a request it does not
     * match skips every route of the group, and one it matches but no route of it does goes on
     * to the next entry, as if the routes were written there flat.
     *
     * @param routeMatches each route's own condition, without the group's ANDed in (which
     *                     {@link DefaultGatewayRoute#match()} has, for display)
     */
    public record Group(String id, Condition match, List<GatewayRoute> routes, List<Condition> routeMatches) implements RoutingEntry
    {
        public Group
        {
            routes = List.copyOf(routes);
            routeMatches = List.copyOf(routeMatches);
            if (routes.size() != routeMatches.size())
            {
                throw new IllegalArgumentException("One condition per route");
            }
        }
    }

    /**
     * One generation of the routing configuration. Published as a whole, so a request that
     * misses every route is refused under the unrouted policy of the same generation it was
     * matched against, never a mix of an old table and a new policy during a hot reload.
     *
     * @param sourceDigest the SHA-256 of the routes file as read, before interpolation, in hex;
     *                     {@code null} for a configuration not loaded from a file
     * @param routes       every route, those in groups included, in the order they are written
     * @param table        what a request is matched against, in order
     * @param unrouted     the journal-only route for requests refused before routing, or
     *                     {@code null} if such requests are not journaled
     */
    public record Snapshot(String version, String sourceDigest, List<GatewayRoute> routes, List<RoutingEntry> table, GatewayRoute unrouted)
    {
        public Snapshot
        {
            routes = List.copyOf(routes);
            table = List.copyOf(table);
        }

        /**
         * A generation without groups: every route at the top level.
         */
        public Snapshot(final String version, final List<GatewayRoute> routes, final GatewayRoute unrouted)
        {
            this(version, null, routes, routes.stream().map(r -> (RoutingEntry) new Single(r)).toList(), unrouted);
        }

        /**
         * @param exchange the exchange being routed: its client request, and its sample draw
         */
        public GatewayRoute findRoute(final ConditionInput exchange)
        {
            for (final RoutingEntry entry : this.table)
            {
                switch (entry)
                {
                    case Single single ->
                    {
                        if (single.match().test(exchange))
                        {
                            return single.route();
                        }
                    }
                    case Group group ->
                    {
                        if (group.match().test(exchange))
                        {
                            final List<Condition> matches = group.routeMatches();
                            for (int i = 0; i < matches.size(); i++)
                            {
                                if (matches.get(i).test(exchange))
                                {
                                    return group.routes().get(i);
                                }
                            }
                        }
                    }
                }
            }
            return null;
        }
    }

    /**
     * Swaps the current routing table for a new one for hot-reload
     */
    public void updateRoutes(final String version, final List<GatewayRoute> newRoutes)
    {
        updateRoutes(version, newRoutes, null);
    }

    /**
     * @param unroutedRoute the route requests refused before routing are journaled under, or
     *                      {@code null} when the configuration has no {@code unrouted} section
     */
    public void updateRoutes(final String version, final List<GatewayRoute> newRoutes, final GatewayRoute unroutedRoute)
    {
        this.publish(new Snapshot(version, newRoutes, unroutedRoute));
    }

    /**
     * Makes the given generation current: routes and unrouted policy in one swap.
     */
    public void publish(final Snapshot snapshot)
    {
        this.current.set(snapshot);
    }

    /**
     * @return the current generation; hold on to it for the whole of a routing decision
     */
    public Snapshot snapshot()
    {
        return this.current.get();
    }

    public GatewayRoute findRoute(final ConditionInput exchange)
    {
        return this.current.get().findRoute(exchange);
    }

    public List<GatewayRoute> getRoutes()
    {
        return this.current.get().routes();
    }

    public String getConfigVersion()
    {
        return this.current.get().version();
    }

    /**
     * @return the SHA-256 of the live routes file as read, or {@code null} if not loaded from one
     */
    public String getSourceDigest()
    {
        return this.current.get().sourceDigest();
    }

    /**
     * @return the journal-only route for requests refused before routing, or {@code null} if
     * such requests are not journaled
     */
    public GatewayRoute unroutedRoute()
    {
        return this.current.get().unrouted();
    }

    public Optional<GatewayRoute> findRoute(String routeId)
    {
        return this.current.get().routes().stream().filter(route -> route.id().equals(routeId)).findFirst();
    }
}
