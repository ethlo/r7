package com.ethlo.r7.config;

import java.util.List;

import com.ethlo.r7.doc.Nullable;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidationResult;

/**
 * Routes sharing a match, filters and upstream. A group is flattened at load: each of its routes
 * gets the group's match ANDed before its own, the group's filter instances before its own, and
 * each upstream key the route does not name itself.
 *
 * @param match optional: a group without one places no condition of its own
 */
public record GroupDefinition(
        String group,
        @Nullable ConditionDefinition match,
        @Nullable List<FilterDefinition> filters,
        @Nullable UpstreamConfig upstream,
        List<RouteDefinition> routes
) implements RouteEntry
{
    @Override
    public String entryId()
    {
        return this.group;
    }

    @Override
    public void validate(final ValidationResult result)
    {
        final ValidatorUtils validator = new ValidatorUtils(result);
        validator.required("group", this.group);
        // Journaled as gateway.route.group on every exchange of its routes.
        validator.journalText("group", this.group);
        if (UnroutedDefinition.ROUTE_ID.equals(this.group))
        {
            result.addError("group", "'" + UnroutedDefinition.ROUTE_ID + "' is reserved for requests refused before routing");
        }

        if (this.upstream != null)
        {
            this.upstream.validate(result.nested("upstream"));
        }

        if (this.routes == null || this.routes.isEmpty())
        {
            result.addError("routes", "Group '" + this.group + "' needs at least one route");
            return;
        }
        for (final RouteDefinition route : this.routes)
        {
            route.validate(result.nested("routes").nested(route.id() != null ? route.id() : "?"));
        }
    }
}
