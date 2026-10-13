package com.ethlo.r7.config;

import java.util.List;

import com.ethlo.r7.validation.ValidationResult;
import tools.jackson.databind.JsonNode;

/**
 * Checks a routes file's tree before it is bound, for what binding cannot report well:
 * <ul>
 *   <li>an entry of {@code routes} that is neither a route nor a group, or both. Binding would
 *   pick one by the key it finds and then fail on the other with "unknown property";</li>
 *   <li>an explicit {@code null} for {@code upstream}, any of its keys, {@code filters} or
 *   {@code journal}. Written under a group it reads as "none here", but nothing can remove what an
 *   outer level declared, and a record cannot tell {@code null} from a key left out;</li>
 *   <li>{@code global_filters} and {@code status_overrides}, removed in 1.0, which deserve their
 *   replacement's name rather than "unknown property".</li>
 * </ul>
 * Paths name entries by their id, as validation does: {@code routes.tenant-a.routes.orders}.
 */
public final class RoutesTreeCheck
{
    static final String GLOBAL_FILTERS_REMOVED = "global_filters was replaced in 1.0 by filters at the root, with the same meaning: one instance of each filter for the whole gateway. Rename the key.";

    static final String STATUS_OVERRIDES_REMOVED = "status_overrides was replaced in 1.0 by journal rules: write an on_response rule matching Status, e.g. on_response: [{match: [{Status: {include: [5xx]}}], response: {level: FULL}}].";

    private static final List<String> UPSTREAM_KEYS = List.of("strategy", "health_check", "timeouts", "targets", "fallback", "preserve_host");

    private RoutesTreeCheck()
    {
    }

    /**
     * @throws ConfigurationException naming every offending entry and key
     */
    public static void check(final JsonNode root)
    {
        if (root == null || !root.isObject())
        {
            return;
        }
        final ValidationResult result = new ValidationResult();
        if (root.has("global_filters"))
        {
            result.addError("global_filters", GLOBAL_FILTERS_REMOVED);
        }
        checkInheritedKeys(root, result);
        final JsonNode unrouted = root.get("unrouted");
        if (unrouted != null && unrouted.isObject())
        {
            checkJournal(unrouted.get("journal"), result.nested("unrouted").nested("journal"));
        }

        final JsonNode routes = root.get("routes");
        if (routes != null && routes.isArray())
        {
            for (int i = 0; i < routes.size(); i++)
            {
                checkEntry(routes.get(i), result, "routes", i, false);
            }
        }
        result.throwIfInvalid();
    }

    private static void checkEntry(final JsonNode entry, final ValidationResult parent, final String listPath, final int index, final boolean inGroup)
    {
        if (entry == null || !entry.isObject())
        {
            return;
        }
        final String id = text(entry.get("id"));
        final String group = text(entry.get("group"));
        final String name = id != null ? id : group;
        final ValidationResult result = name != null ? parent.nested(listPath).nested(name) : parent.nested(listPath + "[" + index + "]");

        if (entry.has("id") && entry.has("group"))
        {
            result.addError(null, "has both 'id' and 'group': a route is written with 'id', a group of routes with 'group'");
            return;
        }
        if (!entry.has("id") && !entry.has("group"))
        {
            result.addError(null, "needs 'id' for a route, or 'group' for a group of routes");
            return;
        }
        if (entry.has("group") && inGroup)
        {
            result.addError(null, "is a group inside a group; groups nest one level, so a group holds routes only");
            return;
        }
        if (entry.has("id") && entry.has("routes"))
        {
            result.addError("routes", "a route cannot hold routes; write 'group: " + id + "' instead of 'id: " + id + "' for a group of routes");
            return;
        }

        checkInheritedKeys(entry, result);

        final JsonNode routes = entry.get("routes");
        if (entry.has("group") && routes != null && routes.isArray())
        {
            for (int i = 0; i < routes.size(); i++)
            {
                checkEntry(routes.get(i), result, "routes", i, true);
            }
        }
    }

    private static void checkInheritedKeys(final JsonNode level, final ValidationResult result)
    {
        final JsonNode upstream = level.get("upstream");
        if (upstream != null && upstream.isNull())
        {
            result.addError("upstream", "null cannot remove an inherited upstream; leave the key out to inherit it.");
        }
        else if (upstream != null && upstream.isObject())
        {
            for (final String key : UPSTREAM_KEYS)
            {
                final JsonNode value = upstream.get(key);
                if (value != null && value.isNull())
                {
                    result.nested("upstream").addError(key, "null cannot remove an inherited " + key + "; leave the key out to inherit it.");
                }
            }
        }
        for (final String key : List.of("filters", "journal"))
        {
            final JsonNode value = level.get(key);
            if (value != null && value.isNull())
            {
                result.addError(key, "null cannot remove inherited " + key + "; leave the key out.");
            }
        }
        checkJournal(level.get("journal"), result.nested("journal"));
    }

    private static void checkJournal(final JsonNode journal, final ValidationResult result)
    {
        if (journal == null || !journal.isObject())
        {
            return;
        }
        for (final String direction : List.of("request", "response"))
        {
            final JsonNode value = journal.get(direction);
            if (value != null && value.isObject() && value.has("status_overrides"))
            {
                result.nested(direction).addError("status_overrides", STATUS_OVERRIDES_REMOVED);
            }
        }
    }

    private static String text(final JsonNode node)
    {
        return node != null && node.isString() ? node.asString() : null;
    }
}
