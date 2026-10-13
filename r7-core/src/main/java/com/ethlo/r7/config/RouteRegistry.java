package com.ethlo.r7.config;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.GatewayRoute;

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
     * A route at the top level, matched by its own predicate.
     */
    public record Single(GatewayRoute route) implements RoutingEntry
    {
    }

    /**
     * A group's routes behind the group's own predicate, tested once: a request it does not
     * match skips every route of the group, and one it matches but no route of it does goes on
     * to the next entry, as if the routes were written there flat.
     *
     * @param routePredicates each route's own predicate, without the group's ANDed in (which
     *                        {@link GatewayRoute#predicate()} has, for display)
     */
    public record Group(String id, GatewayPredicate predicate, List<GatewayRoute> routes, List<GatewayPredicate> routePredicates) implements RoutingEntry
    {
        public Group
        {
            routes = List.copyOf(routes);
            routePredicates = List.copyOf(routePredicates);
            if (routes.size() != routePredicates.size())
            {
                throw new IllegalArgumentException("One predicate per route");
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

        public GatewayRoute findRoute(final GatewayRequest gatewayRequest)
        {
            for (final RoutingEntry entry : this.table)
            {
                switch (entry)
                {
                    case Single single ->
                    {
                        if (single.route().predicate().test(gatewayRequest))
                        {
                            return single.route();
                        }
                    }
                    case Group group ->
                    {
                        if (group.predicate().test(gatewayRequest))
                        {
                            final List<GatewayPredicate> predicates = group.routePredicates();
                            for (int i = 0; i < predicates.size(); i++)
                            {
                                if (predicates.get(i).test(gatewayRequest))
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

    public GatewayRoute findRoute(GatewayRequest gatewayRequest)
    {
        return this.current.get().findRoute(gatewayRequest);
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
