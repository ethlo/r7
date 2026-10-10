package com.ethlo.r7.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.ethlo.r7.api.ComponentStatus;
import com.ethlo.r7.config.HotReloadService;
import com.ethlo.r7.r7f.DiskSpaceUtils;
import com.ethlo.r7.status.dto.FilterNode;
import com.ethlo.r7.status.dto.RouteConfigDto;

/**
 * The gateway's state in one word: the worst that any of its components reports, with every
 * component that is not OK as the reason. It is what the dashboard's header shows, what
 * {@code /health} and {@code /ready} carry in their body and what {@code r7_gateway_health} graphs,
 * so one check reads all of them.
 *
 * @param health   OK, or the worst of the problems
 * @param problems the components that are not OK, in route order; empty when the gateway is OK
 */
public record GatewayHealth(ComponentStatus.Health health, List<Problem> problems)
{
    /**
     * @param route     the route the component belongs to, or null for a global filter (one
     *                  instance every route shares), for routes.yaml itself and for the journal
     * @param component the filter's name, {@code upstream}, {@code routes.yaml} or {@code journal}
     * @param position  the filter's place in its route's pipeline, global filters first; 0 for
     *                  the upstream and null for routes.yaml and the journal
     * @param detail    a short line for a person, or null
     */
    public record Problem(String route, String component, Integer position, ComponentStatus.Health health, String detail)
    {
    }

    /**
     * One component's report, as the metrics and the roll-up both read it.
     *
     * @param route null for a global filter
     */
    record ComponentReport(String route, String component, int position, ComponentStatus status)
    {
    }

    public GatewayHealth
    {
        problems = List.copyOf(problems);
    }

    /**
     * Each route's components that report a status: its filters, numbered by their place in the
     * route's pipeline (global filters first), and its upstream as position 0. A global filter is
     * one instance shared by every route, so it is reported once and without a route: under each
     * route its gateway-wide counts would be summed once per route.
     */
    static List<ComponentReport> components(final List<RouteConfigDto> routes)
    {
        final List<ComponentReport> reported = new ArrayList<>();
        final Set<Integer> globalsReported = new HashSet<>();
        for (final RouteConfigDto route : routes)
        {
            int position = 1;
            for (FilterNode node = route.filterNodes(); node != null; node = node.child())
            {
                final boolean upstream = node.child() == null;
                if (node.status() != null && (!node.global() || globalsReported.add(position)))
                {
                    reported.add(new ComponentReport(node.global() ? null : route.id(), upstream ? "upstream" : node.name(), upstream ? 0 : position, node.status()));
                }
                position++;
            }
        }
        return reported;
    }

    /**
     * @return true while the latest edit of routes.yaml was rejected, so what runs is not what
     * the file says
     */
    static boolean routesRejected(final HotReloadService.Status routeSource)
    {
        return routeSource.rejectedAt() != null && (routeSource.loadedAt() == null || routeSource.rejectedAt().isAfter(routeSource.loadedAt()));
    }

    /**
     * A rejected routes.yaml is a warning, not an error: the previous routes still run and serve
     * traffic, but the next restart would fail on the same file.
     */
    static GatewayHealth of(final List<ComponentReport> components, final HotReloadService.Status routeSource, final List<Problem> journal)
    {
        final List<Problem> problems = new ArrayList<>();
        if (routesRejected(routeSource))
        {
            problems.add(new Problem(null, "routes.yaml", null, ComponentStatus.Health.WARN, rejectedDetail(routeSource.rejectedAt())));
        }
        problems.addAll(journal);
        for (final ComponentReport report : components)
        {
            final ComponentStatus status = report.status();
            if (status.health() != ComponentStatus.Health.OK)
            {
                problems.add(new Problem(report.route(), report.component(), report.position(), status.health(), status.detail()));
            }
        }
        // Health is declared from best to worst
        ComponentStatus.Health worst = ComponentStatus.Health.OK;
        for (final Problem problem : problems)
        {
            if (problem.health().compareTo(worst) > 0)
            {
                worst = problem.health();
            }
        }
        return new GatewayHealth(worst, problems);
    }

    /**
     * A warning while the journal's disk has less free space than {@code neededBytes}: two more
     * segments for every shard. A full disk is the system's error, not r7's, and the entries in
     * flight when it fills are lost, so the point is to see it coming. Free space that cannot be
     * read (negative) is not reported: it says nothing about the disk.
     *
     * @return the problem, or null while there is room
     */
    static Problem journalDisk(final long availableBytes, final long neededBytes)
    {
        if (availableBytes < 0 || availableBytes >= neededBytes)
        {
            return null;
        }
        return new Problem(null, "journal", null, ComponentStatus.Health.WARN,
                "Journal disk has " + DiskSpaceUtils.formatBytes(availableBytes) + " free, less than two more segments per shard ("
                        + DiskSpaceUtils.formatBytes(neededBytes) + "); journaling stops when it is full");
    }

    /**
     * An error for a journal shard that has stopped. A compressed journal places entries on a
     * thread of its own, after their requests have completed, so a failure there (a full disk,
     * a lost mapping) cannot fail those requests; the shard refuses every entry after it
     * instead, and this is how that is seen. It lasts until the gateway restarts.
     *
     * @param shard   the shard's index
     * @param failure what stopped it
     */
    static Problem journalShard(final int shard, final Throwable failure)
    {
        return new Problem(null, "journal", null, ComponentStatus.Health.ERROR,
                "Shard " + shard + " stopped (" + failure + "); requests it would journal fail until the gateway restarts");
    }

    private static String rejectedDetail(final Instant rejectedAt)
    {
        return "Rejected at " + rejectedAt + ": the previous routes still run; the reason is in the gateway log";
    }
}
