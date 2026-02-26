package com.ethlo.venturi.config;

import java.util.Collections;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;

public record FilterDefinition(String name, Object args)
{
    @JsonCreator
    public static FilterDefinition create(Object raw)
    {
        if (raw instanceof String name)
        {
            // Case 1: Zero-config shorthand
            // - CorrelationIdHeader
            return new FilterDefinition(name, Collections.emptyMap());
        }
        else if (raw instanceof Map<?, ?> map)
        {
            if (map.isEmpty())
            {
                throw new IllegalArgumentException("Filter definition map cannot be empty");
            }

            // Since it's a list item, Jackson parses it as a map with exactly ONE key.
            final Map.Entry<?, ?> entry = map.entrySet().iterator().next();
            final String filterName = String.valueOf(entry.getKey());

            // If the user typed "- CorrelationIdHeader:" (with a trailing colon but no args),
            // Jackson sets the value to null. We coerce that to an empty map.
            final Object filterArgs = entry.getValue() == null ? Collections.emptyMap() : entry.getValue();

            return new FilterDefinition(filterName, filterArgs);
        }

        throw new IllegalArgumentException("Invalid filter definition format: " + raw.getClass().getName());
    }
}