package com.ethlo.r7.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.ComponentStatus;
import com.ethlo.r7.config.HotReloadService;
import com.ethlo.r7.status.dto.FilterNode;
import com.ethlo.r7.status.dto.RouteConfigDto;

class GatewayHealthTest
{
    private static final Instant LOADED = Instant.parse("2026-10-08T10:00:00Z");
    private static final HotReloadService.Status LOADED_OK = new HotReloadService.Status("routes.yaml", LOADED, null);

    @Test
    void aGatewayWhoseComponentsAreAllOkIsOk()
    {
        final GatewayHealth health = health(List.of(route("a", node("CircuitBreaker", "route", ComponentStatus.ok(), upstream(ComponentStatus.ok())))), LOADED_OK);
        assertThat(health.health()).isEqualTo(ComponentStatus.Health.OK);
        assertThat(health.problems()).isEmpty();
    }

    @Test
    void theWorstComponentDecidesAndEveryOneNotOkIsListed()
    {
        final GatewayHealth health = health(List.of(
                route("a", node("RateLimiter", "route", status(ComponentStatus.Health.WARN, "full"), upstream(ComponentStatus.ok()))),
                route("b", node("CircuitBreaker", "route", status(ComponentStatus.Health.ERROR, "Open"), upstream(status(ComponentStatus.Health.WARN, "1 of 2 targets down"))))), LOADED_OK);

        assertThat(health.health()).isEqualTo(ComponentStatus.Health.ERROR);
        assertThat(health.problems()).containsExactly(
                new GatewayHealth.Problem("a", "RateLimiter", 1, ComponentStatus.Health.WARN, "full"),
                new GatewayHealth.Problem("b", "CircuitBreaker", 1, ComponentStatus.Health.ERROR, "Open"),
                new GatewayHealth.Problem("b", "upstream", 0, ComponentStatus.Health.WARN, "1 of 2 targets down"));
    }

    /**
     * A root filter is one instance in front of every route: it is one problem, not one per route.
     */
    @Test
    void aRootFilterIsListedOnceWithoutARoute()
    {
        final ComponentStatus open = status(ComponentStatus.Health.ERROR, "Open");
        final GatewayHealth health = health(List.of(
                route("a", node("CircuitBreaker", "root", open, upstream(null))),
                route("b", node("CircuitBreaker", "root", open, upstream(null)))), LOADED_OK);

        assertThat(health.problems()).containsExactly(new GatewayHealth.Problem(null, "CircuitBreaker", 1, ComponentStatus.Health.ERROR, "Open"));
    }

    /**
     * A group's filter is one instance in front of the group's routes: one problem, under the group.
     */
    @Test
    void aGroupFilterIsListedOnceUnderItsGroup()
    {
        final ComponentStatus open = status(ComponentStatus.Health.ERROR, "Open");
        final GatewayHealth health = health(List.of(
                route("a", "g", node("CircuitBreaker", "group", open, upstream(null))),
                route("b", "g", node("CircuitBreaker", "group", open, upstream(null))),
                route("c", "h", node("CircuitBreaker", "group", open, upstream(null)))), LOADED_OK);

        assertThat(health.problems()).containsExactly(
                new GatewayHealth.Problem("g", "CircuitBreaker", 1, ComponentStatus.Health.ERROR, "Open"),
                new GatewayHealth.Problem("h", "CircuitBreaker", 1, ComponentStatus.Health.ERROR, "Open"));
    }

    /**
     * The previous routes still serve traffic, so it is not an error; but what runs is not what the
     * file says, and the next restart would fail on it.
     */
    @Test
    void aRejectedRoutesFileIsAWarning()
    {
        final Instant rejectedAt = LOADED.plusSeconds(60);
        final GatewayHealth health = health(List.of(), new HotReloadService.Status("routes.yaml", LOADED, rejectedAt));

        assertThat(health.health()).isEqualTo(ComponentStatus.Health.WARN);
        assertThat(health.problems()).singleElement().satisfies(p ->
        {
            assertThat(p.route()).isNull();
            assertThat(p.component()).isEqualTo("routes.yaml");
            assertThat(p.position()).isNull();
            assertThat(p.detail()).contains(rejectedAt.toString());
        });
    }

    @Test
    void aRejectionFollowedByASuccessfulLoadIsOk()
    {
        final GatewayHealth health = health(List.of(), new HotReloadService.Status("routes.yaml", LOADED.plusSeconds(120), LOADED.plusSeconds(60)));
        assertThat(health.health()).isEqualTo(ComponentStatus.Health.OK);
    }

    @Test
    void aJournalDiskShortOfTwoSegmentsPerShardIsAWarning()
    {
        final long needed = 2L * 4 * 200 * 1024 * 1024;
        assertThat(GatewayHealth.journalDisk(needed, needed)).as("exactly enough").isNull();
        assertThat(GatewayHealth.journalDisk(-1, needed)).as("unknown free space").isNull();

        final GatewayHealth health = GatewayHealth.of(List.of(), new HotReloadService.Status("routes.yaml", LOADED, null),
                List.of(GatewayHealth.journalDisk(needed - 1, needed)));
        assertThat(health.health()).isEqualTo(ComponentStatus.Health.WARN);
        assertThat(health.problems()).singleElement().satisfies(p ->
        {
            assertThat(p.route()).isNull();
            assertThat(p.component()).isEqualTo("journal");
            assertThat(p.position()).isNull();
            assertThat(p.detail()).contains("two more segments per shard");
        });
    }

    @Test
    void aStoppedJournalShardIsAnError()
    {
        final GatewayHealth health = GatewayHealth.of(List.of(), new HotReloadService.Status("routes.yaml", LOADED, null),
                List.of(GatewayHealth.journalShard(3, new java.io.IOException("No space left on device"))));
        assertThat(health.health()).isEqualTo(ComponentStatus.Health.ERROR);
        assertThat(health.problems()).singleElement().satisfies(p ->
        {
            assertThat(p.component()).isEqualTo("journal");
            assertThat(p.detail()).contains("Shard 3 stopped", "No space left on device", "until the gateway restarts");
        });
    }

    private static GatewayHealth health(final List<RouteConfigDto> routes, final HotReloadService.Status source)
    {
        return GatewayHealth.of(GatewayHealth.components(routes), source, List.of());
    }

    private static RouteConfigDto route(final String id, final FilterNode nodes)
    {
        return route(id, null, nodes);
    }

    private static RouteConfigDto route(final String id, final String group, final FilterNode nodes)
    {
        return new RouteConfigDto(id, group, 1, null, null, null, null, List.of(), nodes);
    }

    private static FilterNode node(final String name, final String level, final ComponentStatus status, final FilterNode child)
    {
        return new FilterNode(name, name, level, true, false, false, false, status, child);
    }

    private static FilterNode upstream(final ComponentStatus status)
    {
        return new FilterNode("upstream", "http://localhost", "route", false, false, false, false, status, null);
    }

    private static ComponentStatus status(final ComponentStatus.Health health, final String detail)
    {
        return new ComponentStatus(health, detail, null);
    }
}
