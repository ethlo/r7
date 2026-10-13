package com.ethlo.r7.config;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * Binds an entry of {@code routes} as a group when it names {@code group}, and as a route
 * otherwise. An entry that is both, or neither, is refused before binding by
 * {@link RoutesTreeCheck}, with a message naming it; a route without an id that still reaches
 * here is reported by {@link RouteDefinition#validate}.
 */
final class RouteEntryDeserializer extends StdDeserializer<RouteEntry>
{
    RouteEntryDeserializer()
    {
        super(RouteEntry.class);
    }

    @Override
    public RouteEntry deserialize(final JsonParser p, final DeserializationContext ctxt)
    {
        final JsonNode node = ctxt.readTree(p);
        if (node.has("group"))
        {
            return ctxt.readTreeAsValue(node, GroupDefinition.class);
        }
        return ctxt.readTreeAsValue(node, RouteDefinition.class);
    }
}
