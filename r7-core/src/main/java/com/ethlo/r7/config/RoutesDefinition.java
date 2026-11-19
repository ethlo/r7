package com.ethlo.r7.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.ethlo.r7.doc.Nullable;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * The root of {@code routes.yaml}: a group without a match and without an id. Its
 * {@code filters} are one instance each for the whole gateway, its {@code upstream} keys and
 * {@code journal} levels are inherited by every route that does not name them, directly or
 * through its group, and its journal rules apply to every route, after the group's and the
 * route's own. Not to {@code unrouted}, which has a journal of its own.
 */
public record RoutesDefinition(
        @Nullable String version,
        @Nullable List<FilterDefinition> filters,
        @Nullable UpstreamConfig upstream,
        @Nullable JournalDefinition journal,
        // On the component rather than on RouteEntry: an annotation there would be inherited by
        // the records the deserializer binds, which would then recurse into it
        @JsonDeserialize(contentUsing = RouteEntryDeserializer.class)
        List<RouteEntry> routes,
        @Nullable UnroutedDefinition unrouted
) implements ValidatableConfig, VersionedConfig
{
    @Override
    public void validate(final ValidationResult result)
    {
        if (this.upstream != null)
        {
            this.upstream.validate(result.nested("upstream"));
        }
        if (this.journal != null)
        {
            this.journal.validate(result.nested("journal"));
        }
        for (final RouteEntry entry : routes())
        {
            entry.validate(result.nested("routes").nested(entry.entryId() != null ? entry.entryId() : "?"));
        }
        if (this.unrouted != null)
        {
            this.unrouted.validate(result.nested("unrouted"));
        }
    }

    @Override
    public List<RouteEntry> routes()
    {
        return Optional.ofNullable(this.routes).orElse(List.of());
    }

    @Override
    public List<FilterDefinition> filters()
    {
        return Optional.ofNullable(this.filters).orElse(List.of());
    }

    /**
     * @return every route, those in groups included, in the order they are written
     */
    public List<RouteDefinition> allRoutes()
    {
        final List<RouteDefinition> result = new ArrayList<>();
        for (final RouteEntry entry : routes())
        {
            switch (entry)
            {
                case RouteDefinition route -> result.add(route);
                case GroupDefinition group -> result.addAll(Optional.ofNullable(group.routes()).orElse(List.of()));
            }
        }
        return result;
    }
}
